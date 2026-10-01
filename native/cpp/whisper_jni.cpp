// JNI glue for whisper.cpp (MIT), used by app.mokuhyo.ai.jni.WhisperJniBridge (via WhisperNative).
// A context is used from one Kotlin worker thread at a time; cancel tokens may be raised from any thread.
#include <jni.h>

#include <string>
#include <vector>

#include "jni_common.h"
#include "whisper.h"

using mokuhyo::AbortState;
using mokuhyo::CancelToken;

namespace {

struct Session {
    whisper_context *ctx = nullptr;
    AbortState abort;
};

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeNewCancelToken(JNIEnv *, jclass) {
    return reinterpret_cast<jlong>(new CancelToken());
}

JNIEXPORT void JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeCancelThrough(JNIEnv *, jclass, jlong token, jlong through) {
    auto *t = reinterpret_cast<CancelToken *>(token);
    if (t == nullptr) return;
    int64_t cur = t->through.load();
    while (cur < through && !t->through.compare_exchange_weak(cur, through)) {
    }
}

/// Loads a ggml Whisper model. [use_gpu]: run on the GPU backend (Metal/Vulkan) when the library has one.
JNIEXPORT jlong JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeInit(JNIEnv *env, jclass, jbyteArray jpath, jboolean use_gpu) {
    const std::string path = mokuhyo::to_string(env, jpath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = use_gpu == JNI_TRUE;
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) return 0;
    auto *s = new Session();
    s->ctx = ctx;
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return;
    whisper_free(s->ctx);
    delete s;
}

/// True when whisper knows the language code ("ja", "es", "zh", …) or it is "auto".
JNIEXPORT jboolean JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeIsLanguage(JNIEnv *env, jclass, jstring jlanguage) {
    const char *lang = env->GetStringUTFChars(jlanguage, nullptr);
    const bool ok = std::string(lang) == "auto" || whisper_lang_id(lang) >= 0;
    env->ReleaseStringUTFChars(jlanguage, lang);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/// Runs whisper_full on 16 kHz mono [jsamples]. Returns the number of segments, -1000 when cancelled through
/// [token]/[id], or another negative error code.
JNIEXPORT jint JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeTranscribe(JNIEnv *env, jclass, jlong handle, jfloatArray jsamples,
                                                       jstring jlanguage, jint n_threads, jlong token, jlong id) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return -100;
    s->abort.token = reinterpret_cast<CancelToken *>(token);
    s->abort.id = id;
    if (s->abort.cancelled()) return -1000;

    const jsize n = env->GetArrayLength(jsamples);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jsamples, 0, n, samples.data());
    const char *lang = env->GetStringUTFChars(jlanguage, nullptr);
    const std::string language(lang);
    env->ReleaseStringUTFChars(jlanguage, lang);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads;
    params.translate = false;
    params.no_context = true;        // every utterance stands alone
    params.no_timestamps = false;    // segment timestamps are needed
    params.token_timestamps = false; // token-level timestamps are not
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.language = language.c_str();
    params.detect_language = false;
    params.abort_callback = mokuhyo::abort_if_cancelled;
    params.abort_callback_user_data = &s->abort;

    const int status = whisper_full(s->ctx, params, samples.data(), static_cast<int>(samples.size()));
    if (s->abort.cancelled()) return -1000;
    if (status != 0) return status < 0 ? status : -status;
    return whisper_full_n_segments(s->ctx);
}

/// Segment [i] as {t0 ms, t1 ms}.
JNIEXPORT jlongArray JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeSegmentTimes(JNIEnv *env, jclass, jlong handle, jint i) {
    auto *s = reinterpret_cast<Session *>(handle);
    // whisper reports segment times in 10 ms units
    const jlong times[2] = {whisper_full_get_segment_t0(s->ctx, i) * 10, whisper_full_get_segment_t1(s->ctx, i) * 10};
    jlongArray out = env->NewLongArray(2);
    env->SetLongArrayRegion(out, 0, 2, times);
    return out;
}

/// Segment [i]'s text as UTF-8 bytes (JNI's NewStringUTF would mangle characters outside the BMP).
JNIEXPORT jbyteArray JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeSegmentText(JNIEnv *env, jclass, jlong handle, jint i) {
    auto *s = reinterpret_cast<Session *>(handle);
    const char *text = whisper_full_get_segment_text(s->ctx, i);
    return mokuhyo::to_bytes(env, text != nullptr ? text : "");
}

JNIEXPORT jstring JNICALL
Java_app_mokuhyo_ai_jni_WhisperNative_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

} // extern "C"
