#include <jni.h>
#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <kaldi-native-fbank/csrc/online-feature.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr int kBins=64, kVocabulary=257, kBlank=256, kMaxSamples=20*16000;
struct Cancelled {};
void require(bool ok,const char* message) { if(!ok)throw std::runtime_error(message); }
double elapsed(Clock::time_point start) { return std::chrono::duration<double,std::milli>(Clock::now()-start).count(); }
struct Engine;
thread_local Engine* activeEngine=nullptr;
struct Engine {
 std::unique_ptr<MNN::Interpreter> model;
 MNN::Session* session=nullptr;
 MNN::Tensor* input=nullptr;
 MNN::Tensor* lengths=nullptr;
 std::atomic<bool> cancelled{false};
 int frames=0, gpuOps=0, foreignOps=0;
 std::string foreignOp, stats;
 double loadMs=0, warmMs=0;
 void checkActive() const { if(cancelled.load())throw Cancelled{}; }
 void resetPlacement() { gpuOps=foreignOps=0;foreignOp.clear(); }
 void record(const char* name,const char* type,int backend) {
  if(backend==MNN_FORWARD_OPENCL)++gpuOps;
  else { ++foreignOps; if(foreignOp.empty())foreignOp=std::string(type)+" ("+name+"), backend="+std::to_string(backend); }
 }
 void verifyPlacement() const {
  if(foreignOps)throw std::runtime_error("GigaAM GPU refused CPU/unknown operator: "+foreignOp);
  require(gpuOps>0,"GigaAM GPU placement hook did not observe OpenCL execution");
 }
 void resize(int requested) {
  checkActive();
  if(frames==requested)return;
  model->resizeTensor(input,{1,kBins,requested});model->resizeTensor(lengths,{1});
  model->resizeSession(session);
  checkActive();
  int backends[8]={-1,-1,-1,-1,-1,-1,-1,-1};
  require(model->getSessionInfo(session,MNN::Interpreter::BACKENDS,backends),"GigaAM backend query failed");
  require(backends[0]==MNN_FORWARD_OPENCL,"GigaAM OpenCL unavailable; CPU fallback refused");
  frames=requested;
 }
 std::vector<float> infer(const std::vector<float>& features,int count) {
  checkActive();require(count>0 && count<=2000,"GigaAM feature length exceeds 20 seconds");
  require(features.size()==static_cast<size_t>(kBins*count),"GigaAM feature shape mismatch");
  const auto start=Clock::now();resize(count);
  auto* output=model->getSessionOutput(session,"log_probs");require(output,"GigaAM log_probs output missing");
  MNN::Tensor hostInput(input,MNN::Tensor::CAFFE);
  require(hostInput.getType().code==halide_type_float && hostInput.getType().bits==32,"GigaAM input must be FP32");
  require(hostInput.elementSize()==kBins*count,"GigaAM input allocation mismatch");
  for(float v:features)require(std::isfinite(v),"GigaAM frontend produced non-finite features");
  std::copy(features.begin(),features.end(),hostInput.host<float>());
  require(input->copyFromHostTensor(&hostInput),"GigaAM feature transfer failed");checkActive();
  MNN::Tensor hostLengths(lengths,MNN::Tensor::CAFFE);
  require(hostLengths.getType().code==halide_type_int,"GigaAM lengths must be integer");
  if(hostLengths.getType().bits==64)hostLengths.host<int64_t>()[0]=count;
  else if(hostLengths.getType().bits==32)hostLengths.host<int32_t>()[0]=count;
  else throw std::runtime_error("GigaAM unsupported length integer width");
  require(lengths->copyFromHostTensor(&hostLengths),"GigaAM length transfer failed");checkActive();
  resetPlacement();activeEngine=this;
  // The pinned hook runs before callback-covered operators: skip foreign ones
  // and stop the graph. Internal commands without callbacks can execute; the
  // final placement check rejects their result if any backend was not OpenCL.
  MNN::TensorCallBackWithInfo before=[&](const std::vector<MNN::Tensor*>&,const MNN::OperatorInfo*) { return foreignOps==0 && !cancelled.load(); };
  MNN::TensorCallBackWithInfo after=[&](const std::vector<MNN::Tensor*>&,const MNN::OperatorInfo*) { return foreignOps==0 && !cancelled.load(); };
  auto code=model->runSessionWithCallBackInfo(session,before,after,true);
  activeEngine=nullptr;
  // MNN3.6.1 Session::runWithCallBack ignores its sync parameter. Always drain
  // the OpenCL queue before cancellation/errors can skip readback and permit a
  // later resize/close. Tensor::wait delegates to OpenCLBackend::onSync, which
  // calls commandQueue.finish(). Read both boundary backends for error paths.
  const int outputWait=output->wait(MNN::Tensor::MAP_TENSOR_READ,true);
  const int inputWait=input->wait(MNN::Tensor::MAP_TENSOR_READ,true);
  require(outputWait==0 && inputWait==0,"GigaAM GPU synchronization failed");
  // Synchronization and readback belong to execution time, not enqueue latency.
  checkActive();verifyPlacement();require(code==MNN::NO_ERROR,"GigaAM GPU execution failed");
  MNN::Tensor hostOutput(output,MNN::Tensor::CAFFE);
  require(output->copyToHostTensor(&hostOutput),"GigaAM GPU readback failed");checkActive();
  require(hostOutput.getType().code==halide_type_float && hostOutput.getType().bits==32,"GigaAM output must be FP32");
  require(hostOutput.dimensions()==3 && hostOutput.length(0)==1 && hostOutput.length(2)==kVocabulary,"GigaAM unexpected logit shape");
  const int n=hostOutput.elementSize();require(n>0 && n%kVocabulary==0 && n<=2000*kVocabulary,"GigaAM invalid logit size");
  std::vector<float> logits(hostOutput.host<float>(),hostOutput.host<float>()+n);
  for(float v:logits)require(std::isfinite(v),"GigaAM GPU produced non-finite logits");
  std::ostringstream info;info<<"MNN=3.6.1 requested=OpenCL precision=FP32 tuning=none memory=buffer frames="<<count<<" output_frames="<<n/kVocabulary<<" gpu_ops="<<gpuOps<<" foreign_ops="<<foreignOps<<" resize_transfer_invoke_readback_ms="<<elapsed(start)<<" load_ms="<<loadMs<<" warm_ms="<<warmMs;
  stats=info.str();__android_log_print(ANDROID_LOG_INFO,"LacanGigaGpu","%s",stats.c_str());
  return logits;
 }
 explicit Engine(const std::string& path) {
  const auto start=Clock::now();model.reset(MNN::Interpreter::createFromFile(path.c_str()));require(bool(model),"Cannot load GigaAM MNN model");loadMs=elapsed(start);
  model->setSessionMode(MNN::Interpreter::Session_Debug);
  model->setSessionMode(MNN::Interpreter::Session_Backend_Fix);
  model->setSessionMode(MNN::Interpreter::Session_Resize_Defer);
  MNN::BackendConfig precision;precision.precision=MNN::BackendConfig::Precision_High;
  MNN::ScheduleConfig config;config.type=MNN_FORWARD_OPENCL;config.mode=MNN_GPU_TUNING_NONE | MNN_GPU_MEMORY_BUFFER;config.backupType=MNN_FORWARD_CPU;config.backendConfig=&precision;
  const auto warm=Clock::now();session=model->createSession(config);require(session,"Cannot create GigaAM OpenCL session");
  input=model->getSessionInput(session,"features");lengths=model->getSessionInput(session,"feature_lengths");require(input && lengths,"GigaAM model inputs missing");
  infer(std::vector<float>(kBins*600,0.f),600);warmMs=elapsed(warm);
 }
 std::vector<int> transcribe(const std::vector<float>& audio) {
  checkActive();require(audio.size()<=kMaxSamples,"GigaAM GPU accepts at most 20 seconds per segment");
  for(float v:audio)require(std::isfinite(v),"Audio contains non-finite samples");
  if(audio.empty())return {};
  knf::FbankOptions options;
  options.frame_opts.samp_freq=16000;options.frame_opts.frame_length_ms=25;options.frame_opts.frame_shift_ms=10;
  options.frame_opts.dither=0;options.frame_opts.snip_edges=false;options.frame_opts.remove_dc_offset=false;options.frame_opts.preemph_coeff=0;
  options.frame_opts.window_type="hann";options.frame_opts.round_to_power_of_two=false;
  options.mel_opts.is_librosa=false;options.mel_opts.htk_mode=false;
  options.mel_opts.num_bins=kBins;options.mel_opts.low_freq=0;options.mel_opts.high_freq=8000;
  knf::OnlineFbank fbank(options);fbank.AcceptWaveform(16000,audio.data(),audio.size());fbank.InputFinished();
  const int count=fbank.NumFramesReady();if(count==0)return {};
  std::vector<float> features(kBins*count);
  // Exact pinned model has no normalize_type metadata. No per-feature normalization.
  for(int t=0;t<count;++t){const float* frame=fbank.GetFrame(t);for(int c=0;c<kBins;++c)features[c*count+t]=frame[c];}
  checkActive();const auto logits=infer(features,count);std::vector<int> ids;int previous=-1;
  for(size_t i=0;i<logits.size();i+=kVocabulary){
   const int best=std::max_element(logits.begin()+i,logits.begin()+i+kVocabulary)-(logits.begin()+i);
   if(best!=previous && best!=kBlank)ids.push_back(best);previous=best;
  }
  checkActive();return ids;
 }
};
void fail(JNIEnv* env,const char* text) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),text); }
Engine* engine(jlong handle) { require(handle!=0,"GigaAM engine is closed");return reinterpret_cast<Engine*>(handle); }
}
// Defined by the pinned diagnostic-only MNN Pipeline instrumentation. No speech is recorded.
extern "C" void lacan_mnn_record_execution(const char* name,const char* type,int backend) { if(activeEngine)activeEngine->record(name,type,backend); }
extern "C" JNIEXPORT jlong JNICALL Java_org_lacan_asr_MnnGigaAmEngine_nativeLoad(JNIEnv* env,jobject,jstring path) {
 try {
  const char* p=env->GetStringUTFChars(path,nullptr);if(!p)return 0;std::string modelPath(p);env->ReleaseStringUTFChars(path,p);
  return reinterpret_cast<jlong>(new Engine(modelPath));
 }catch(const std::exception& e){activeEngine=nullptr;fail(env,e.what());return 0;}
}
extern "C" JNIEXPORT jintArray JNICALL Java_org_lacan_asr_MnnGigaAmEngine_nativeTranscribe(JNIEnv* env,jobject,jlong handle,jfloatArray audio) {
 try{
  auto* e=engine(handle);const auto n=env->GetArrayLength(audio);require(n<=kMaxSamples,"GigaAM GPU accepts at most 20 seconds per segment");
  std::vector<float> samples(n);env->GetFloatArrayRegion(audio,0,n,samples.data());if(env->ExceptionCheck())return nullptr;
  const auto ids=e->transcribe(samples);auto output=env->NewIntArray(ids.size());if(output)env->SetIntArrayRegion(output,0,ids.size(),ids.data());return output;
 }catch(const Cancelled&){activeEngine=nullptr;return env->NewIntArray(0);}catch(const std::exception& e){activeEngine=nullptr;fail(env,e.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_org_lacan_asr_MnnGigaAmEngine_nativeCancel(JNIEnv*,jobject,jlong handle){ if(handle)reinterpret_cast<Engine*>(handle)->cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL Java_org_lacan_asr_MnnGigaAmEngine_nativeBegin(JNIEnv*,jobject,jlong handle){ if(handle)reinterpret_cast<Engine*>(handle)->cancelled.store(false); }
extern "C" JNIEXPORT jstring JNICALL Java_org_lacan_asr_MnnGigaAmEngine_nativeStats(JNIEnv* env,jobject,jlong handle){ return env->NewStringUTF(reinterpret_cast<Engine*>(handle)->stats.c_str()); }
extern "C" JNIEXPORT void JNICALL Java_org_lacan_asr_MnnGigaAmEngine_nativeFree(JNIEnv*,jobject,jlong handle){ delete reinterpret_cast<Engine*>(handle); }
