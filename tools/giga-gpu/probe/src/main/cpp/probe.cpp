#include <jni.h>
#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <core/Backend.hpp>
#include <chrono>
#include <cmath>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <vector>
#include <limits>

namespace {
thread_local std::map<int,int>* executionCounts=nullptr;
thread_local std::ostringstream* executionRows=nullptr;
}
extern "C" void lacan_mnn_record_execution(const char* name,const char* type,int backend) {
 if(executionCounts)(*executionCounts)[backend]++;
 if(executionRows)(*executionRows)<<name<<"\t"<<type<<"\t"<<backend<<"\n";
}
namespace {
using Clock = std::chrono::steady_clock;
double ms(Clock::time_point start) { return std::chrono::duration<double,std::milli>(Clock::now()-start).count(); }
std::string str(JNIEnv* env,jstring v) { const char* p=env->GetStringUTFChars(v,nullptr);std::string s(p);env->ReleaseStringUTFChars(v,p);return s; }
void ensure(bool value,const char* message) { if(!value) throw std::runtime_error(message); }
std::string run(const std::string& root,const std::string& requested,int frames,int length) {
 ensure(requested=="gpu" || requested=="cpu","backend must be gpu or cpu");
 const auto diagnosticStart=Clock::now();
 auto stage=[&](const char* value) {
  std::ostringstream line;line<<"elapsed_ms="<<ms(diagnosticStart)<<" stage="<<value;
  std::ofstream progress(root+"/progress-"+requested+".txt",std::ios::app);
  progress<<line.str()<<"\n";progress.flush();
  __android_log_print(ANDROID_LOG_INFO,"GigaGpuProbe","%s",line.str().c_str());
 };
 stage("native_begin");
 std::ifstream input(root+"/features.f32le",std::ios::binary|std::ios::ate);
 ensure(input.good(),"features.f32le missing");
 const size_t count=64ULL*frames;
 ensure(input.tellg()==static_cast<std::streamoff>(count*4),"feature bytes must equal 64*T*4");
 input.seekg(0);std::vector<float> features(count);input.read(reinterpret_cast<char*>(features.data()),count*4);
 ensure(input.good(),"feature read failed");for(float x:features)ensure(std::isfinite(x),"nonfinite input");
 stage("model_load_begin");
 const auto start=Clock::now();
 std::unique_ptr<MNN::Interpreter> model(MNN::Interpreter::createFromFile((root+"/model.mnn").c_str()));
 ensure(bool(model),"model load failed");const double loadMs=ms(start);stage("model_load_done");
 MNN::BackendConfig bc;bc.precision=MNN::BackendConfig::Precision_High;
 MNN::ScheduleConfig cfg;cfg.type=requested=="gpu"?MNN_FORWARD_OPENCL:MNN_FORWARD_CPU;cfg.numThread=2;
 if(requested=="gpu")cfg.mode=MNN_GPU_TUNING_NONE | MNN_GPU_MEMORY_BUFFER;
 // MNN may schedule unsupported ops on this backup. Always expose those placements.
 cfg.backupType=MNN_FORWARD_CPU;cfg.backendConfig=&bc;
 model->setSessionMode(MNN::Interpreter::Session_Debug);
 model->setSessionMode(MNN::Interpreter::Session_Backend_Fix);
 model->setSessionMode(MNN::Interpreter::Session_Resize_Defer);
 stage("session_create_begin");
 const auto init=Clock::now();auto* session=model->createSession(cfg);ensure(session,"session creation failed");stage("session_create_done");
 auto* x=model->getSessionInput(session,"features");auto* len=model->getSessionInput(session,"feature_lengths");
 ensure(x && len,"expected features and feature_lengths inputs missing");
 stage("resize_begin");
 model->resizeTensor(x,{1,64,frames});model->resizeTensor(len,{1});model->resizeSession(session);
 const double initMs=ms(init);stage("resize_done");
 int selected[8]={-1,-1,-1,-1,-1,-1,-1,-1};ensure(model->getSessionInfo(session,MNN::Interpreter::BACKENDS,selected),"backend query failed");
 if(requested=="gpu")ensure(selected[0]==MNN_FORWARD_OPENCL,"OpenCL unavailable: session selected CPU; refusing automatic full CPU invocation");
 stage("input_copy_begin");
 MNN::Tensor hx(x,MNN::Tensor::CAFFE);ensure(hx.getType().code==halide_type_float && hx.getType().bits==32,"features not FP32");
 ensure(hx.elementSize()==static_cast<int>(count),"input shape mismatch");std::copy(features.begin(),features.end(),hx.host<float>());ensure(x->copyFromHostTensor(&hx),"feature copy failed");
 MNN::Tensor hl(len,MNN::Tensor::CAFFE);ensure(hl.getType().code==halide_type_int,"length is not integer");
 if(hl.getType().bits==64)hl.host<int64_t>()[0]=length;else if(hl.getType().bits==32)hl.host<int32_t>()[0]=length;else throw std::runtime_error("length integer width unsupported");
 ensure(len->copyFromHostTensor(&hl),"length copy failed");stage("input_copy_done");
 std::map<int,int> backends;std::ostringstream placements;
 MNN::TensorCallBackWithInfo before=[](const std::vector<MNN::Tensor*>&,const MNN::OperatorInfo*){return true;};
 MNN::TensorCallBackWithInfo after=[&](const std::vector<MNN::Tensor*>& tensors,const MNN::OperatorInfo* info){
  int kind=-1;
  if(!tensors.empty()){auto* backend=model->getBackend(session,tensors[0]);if(backend)kind=static_cast<int>(backend->type());}
  backends[kind]++;placements<<info->name()<<"\t"<<info->type()<<"\t"<<kind<<"\n";return true;
 };
 std::map<int,int> actualBackends;std::ostringstream actualRows;executionCounts=&actualBackends;executionRows=&actualRows;
 stage("inference_begin");
 const auto invoke=Clock::now();auto result=model->runSessionWithCallBackInfo(session,before,after,true);const double runMs=ms(invoke);executionCounts=nullptr;executionRows=nullptr;stage("inference_done");
 std::ofstream(root+"/execution-"+requested+".tsv")<<"name\top\texecution_backend_id\n"<<actualRows.str();
 std::ofstream(root+"/placement-"+requested+".tsv")<<"name\top\tstorage_backend_id\n"<<placements.str();
 ensure(result==MNN::NO_ERROR,"inference returned error; see execution TSV");auto* out=model->getSessionOutput(session,"log_probs");ensure(out,"log_probs output missing");
 stage("readback_begin");
 const auto read=Clock::now();MNN::Tensor ho(out,MNN::Tensor::CAFFE);ensure(out->copyToHostTensor(&ho),"logit readback failed");
 stage("readback_done");
 ensure(ho.getType().code==halide_type_float && ho.getType().bits==32,"logits not FP32");
 const int size=ho.elementSize();ensure(size>0,"empty logits");float lo=std::numeric_limits<float>::infinity(),hi=-lo;
 for(int i=0;i<size;i++){float v=ho.host<float>()[i];ensure(std::isfinite(v),"nonfinite logits");lo=std::min(lo,v);hi=std::max(hi,v);}
 std::ofstream logits(root+"/logits-"+requested+".f32le",std::ios::binary);logits.write(reinterpret_cast<const char*>(ho.host<float>()),size*4ULL);ensure(logits.good(),"logit file write failed");
 std::ofstream(root+"/placement-"+requested+".tsv")<<"name\top\tbackend_id\n"<<placements.str();
 std::ofstream(root+"/execution-"+requested+".tsv")<<"name\top\texecution_backend_id\n"<<actualRows.str();
 std::ostringstream report;report<<"requested="<<requested<<" precision=High(FP32 storage+compute) tuning=none memory=buffer MNN=3.6.1\nload_ms="<<loadMs<<" init_resize_ms="<<initMs<<" invoke_callback_sync_ms="<<runMs<<" read_save_ms="<<ms(read)<<"\ninput=[1,64,"<<frames<<"] valid_frames="<<length<<" length_bits="<<static_cast<int>(hl.getType().bits)<<"\noutput_shape=[";
 for(int i=0;i<ho.dimensions();i++)report<<(i?",":"")<<ho.length(i);
 report<<"] floats="<<size<<" min="<<lo<<" max="<<hi<<"\nbackend_ids(0=CPU,3=OpenCL):";
 for(auto entry:actualBackends)report<<" "<<entry.first<<"="<<entry.second;
 report<<"\nplacement evidence=instrumented MNN Pipeline::executeCallBack scheduled execution backend; execution TSV includes wrapped backend ID; output storage separately in placement TSV; see placement-"<<requested<<".tsv\n";
 bool otherBackend=false;for(auto entry:actualBackends)if(entry.first!=3 && entry.second>0)otherBackend=true;
 if(requested=="gpu" && (otherBackend || actualBackends[3]==0))report<<"GPU_RESIDENCY_UNQUALIFIED: CPU/unknown placement or no OpenCL execution. No CPU retry performed.\n";
 else report<<"Requested backend scheduled execution observed. Numerical parity and task accuracy require separate comparison.\n";
 stage("native_done");
 return report.str();
}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_lacan_gigaprobe_ProbeActivity_runNative(JNIEnv* env,jobject,jstring root,jstring backend,jint frames,jint length) {
 std::string report;try{report=run(str(env,root),str(env,backend),frames,length);}catch(const std::exception& e){report=std::string("FAILED ")+e.what();}return env->NewStringUTF(report.c_str());
}
