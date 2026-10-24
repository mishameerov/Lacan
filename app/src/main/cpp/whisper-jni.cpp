#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <algorithm>
#include <string>
#include <thread>
#include <vector>

struct Engine {
    whisper_context * context;
    std::atomic<bool> cancelled{false};
};
static void quiet(ggml_log_level, const char *, void *) {}
static bool abort_inference(void * data) { return static_cast<Engine *>(data)->cancelled.load(); }
static void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
extern "C" JNIEXPORT jlong JNICALL
Java_org_lacan_asr_WhisperEngine_nativeLoad(JNIEnv *env, jobject, jstring path) {
    whisper_log_set(quiet, nullptr);
    const char * p = env->GetStringUTFChars(path, nullptr);
    auto config = whisper_context_default_params();
    config.use_gpu = false;
    auto context = whisper_init_from_file_with_params(p, config);
    env->ReleaseStringUTFChars(path, p);
    if (!context) return 0;
    return reinterpret_cast<jlong>(new Engine{context});
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_lacan_asr_WhisperEngine_nativeTranscribe(JNIEnv *env, jobject, jlong handle, jfloatArray audio, jstring language) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    engine->cancelled.store(false);
    const auto count = env->GetArrayLength(audio);
    std::vector<float> samples(count);
    env->GetFloatArrayRegion(audio, 0, count, samples.data());
    if (env->ExceptionCheck()) return nullptr;
    const char * lang = env->GetStringUTFChars(language, nullptr);
    std::string selected(lang);
    env->ReleaseStringUTFChars(language, lang);
    if (selected.empty() || selected == "mixed") selected = "auto";
    // Each window detects independently. Disposable previews must not set the
    // language for authoritative final chunks (especially short/noisy previews).
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    params.language = selected.c_str();
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.abort_callback = abort_inference;
    params.abort_callback_user_data = engine;
    const int result = whisper_full(engine->context, params, samples.data(), count);
    std::string text;
    if (!engine->cancelled.load()) {
        if (result != 0) { fail(env, "Whisper inference failed"); return nullptr; }
        const int segments = whisper_full_n_segments(engine->context);
        for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(engine->context, i);
    }
    // JNI modified UTF-8 cannot represent every Unicode scalar. Kotlin decodes standard UTF-8.
    auto out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out) env->SetByteArrayRegion(out, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}
extern "C" JNIEXPORT void JNICALL
Java_org_lacan_asr_WhisperEngine_nativeCancel(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Engine *>(handle)->cancelled.store(true);
}
extern "C" JNIEXPORT void JNICALL
Java_org_lacan_asr_WhisperEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * engine = reinterpret_cast<Engine *>(handle);
    whisper_free(engine->context);
    delete engine;
}
