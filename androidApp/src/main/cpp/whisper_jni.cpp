// JNI glue for whisper.cpp (MIT), used by app.tsumugi.android.platform.WhisperJni.
// A context is used from one Kotlin worker thread at a time.
#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "whisper.h"

#define TAG "tsumugi-whisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_tsumugi_android_platform_WhisperNative_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // CPU backend only on Android
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    if (ctx != nullptr) LOGI("loaded speech model");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_app_tsumugi_android_platform_WhisperNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) whisper_free(reinterpret_cast<whisper_context *>(handle));
}

/// Runs whisper_full on 16 kHz mono [jsamples]. Returns the number of segments, or a negative error code.
JNIEXPORT jint JNICALL
Java_app_tsumugi_android_platform_WhisperNative_nativeTranscribe(JNIEnv *env, jclass, jlong handle,
                                                                 jfloatArray jsamples, jstring jlanguage,
                                                                 jint n_threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return -100;
    const jsize n = env->GetArrayLength(jsamples);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jsamples, 0, n, samples.data());
    const char *lang = env->GetStringUTFChars(jlanguage, nullptr);
    const std::string language(lang);
    env->ReleaseStringUTFChars(jlanguage, lang);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = n_threads;
    params.translate = false;
    params.no_timestamps = false;    // segment timestamps are needed for subtitles
    params.token_timestamps = false; // token-level timestamps are not
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.language = language.c_str();

    const int status = whisper_full(ctx, params, samples.data(), static_cast<int>(samples.size()));
    if (status != 0) return status < 0 ? status : -status;
    return whisper_full_n_segments(ctx);
}

/// Segment [i] as {t0 ms, t1 ms}.
JNIEXPORT jlongArray JNICALL
Java_app_tsumugi_android_platform_WhisperNative_nativeSegmentTimes(JNIEnv *env, jclass, jlong handle, jint i) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    // whisper reports segment times in 10 ms units
    const jlong times[2] = {whisper_full_get_segment_t0(ctx, i) * 10, whisper_full_get_segment_t1(ctx, i) * 10};
    jlongArray out = env->NewLongArray(2);
    env->SetLongArrayRegion(out, 0, 2, times);
    return out;
}

/// Segment [i]'s text as UTF-8 bytes (JNI's NewStringUTF would mangle characters outside the BMP).
JNIEXPORT jbyteArray JNICALL
Java_app_tsumugi_android_platform_WhisperNative_nativeSegmentText(JNIEnv *env, jclass, jlong handle, jint i) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    const char *text = whisper_full_get_segment_text(ctx, i);
    const std::string s = text != nullptr ? text : "";
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

} // extern "C"
