// Library-wide JNI: backend init, quiet logging, build variant, and the ggml backend device registry (real GPU
// names and VRAM for the hardware probe). Used by app.mokuhyo.ai.jni.NativeLibrary / NativeDevices via GgmlNative.
#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include "ggml-backend.h"
#include "jni_common.h"
#include "llama.h"
#include "whisper.h"

#if defined(__APPLE__)
#include <sys/sysctl.h>
#endif

namespace {

bool g_verbose = false;
thread_local ggml_log_level g_last_level = GGML_LOG_LEVEL_INFO;

/// llama.cpp/whisper.cpp/ggml are chatty on stderr; keep warnings and errors unless MOKUHYO_NATIVE_LOG=1.
void log_callback(ggml_log_level level, const char *text, void *) {
    if (level != GGML_LOG_LEVEL_CONT) g_last_level = level;
    if (g_verbose || g_last_level >= GGML_LOG_LEVEL_WARN) std::fputs(text, stderr);
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    const char *env = std::getenv("MOKUHYO_NATIVE_LOG");
    g_verbose = env != nullptr && std::strcmp(env, "0") != 0 && *env != '\0';
    llama_log_set(log_callback, nullptr);
    whisper_log_set(log_callback, nullptr);
    llama_backend_init();
    return JNI_VERSION_1_8;
}

/// "cpu", "metal" or "vulkan": what this binary was built as.
JNIEXPORT jstring JNICALL
Java_app_mokuhyo_ai_jni_GgmlNative_nativeVariant(JNIEnv *env, jclass) {
    return env->NewStringUTF(MOKUHYO_VARIANT);
}

/// Performance-core count where the OS reports it (Apple silicon), else physical cores, else 0 (unknown).
JNIEXPORT jint JNICALL
Java_app_mokuhyo_ai_jni_GgmlNative_nativePerformanceCores(JNIEnv *, jclass) {
#if defined(__APPLE__)
    int value = 0;
    size_t size = sizeof(value);
    if (sysctlbyname("hw.perflevel0.physicalcpu", &value, &size, nullptr, 0) == 0 && value > 0) return value;
    size = sizeof(value);
    if (sysctlbyname("hw.physicalcpu", &value, &size, nullptr, 0) == 0 && value > 0) return value;
#endif
    return 0;
}

JNIEXPORT jint JNICALL
Java_app_mokuhyo_ai_jni_GgmlNative_nativeDeviceCount(JNIEnv *, jclass) {
    return static_cast<jint>(ggml_backend_dev_count());
}

/// Device [i] as {name, description, backend registry name, type} where type is "cpu", "gpu", "igpu" or "accel".
JNIEXPORT jobjectArray JNICALL
Java_app_mokuhyo_ai_jni_GgmlNative_nativeDeviceInfo(JNIEnv *env, jclass, jint i) {
    if (i < 0 || static_cast<size_t>(i) >= ggml_backend_dev_count()) return nullptr;
    ggml_backend_dev_t dev = ggml_backend_dev_get(static_cast<size_t>(i));
    const char *type = "accel";
    switch (ggml_backend_dev_type(dev)) {
        case GGML_BACKEND_DEVICE_TYPE_CPU: type = "cpu"; break;
        case GGML_BACKEND_DEVICE_TYPE_GPU: type = "gpu"; break;
        case GGML_BACKEND_DEVICE_TYPE_IGPU: type = "igpu"; break;
        default: break;
    }
    const char *name = ggml_backend_dev_name(dev);
    const char *description = ggml_backend_dev_description(dev);
    const char *reg = ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev));
    const char *fields[4] = {name ? name : "", description ? description : "", reg ? reg : "", type};
    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(4, string_class, nullptr);
    for (jsize k = 0; k < 4; ++k) {
        // Driver/vendor strings are ASCII in practice, so modified UTF-8 is fine here.
        jstring s = env->NewStringUTF(fields[k]);
        env->SetObjectArrayElement(out, k, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

/// Device [i] memory as {free, total} bytes.
JNIEXPORT jlongArray JNICALL
Java_app_mokuhyo_ai_jni_GgmlNative_nativeDeviceMemory(JNIEnv *env, jclass, jint i) {
    if (i < 0 || static_cast<size_t>(i) >= ggml_backend_dev_count()) return nullptr;
    size_t free_bytes = 0, total_bytes = 0;
    ggml_backend_dev_memory(ggml_backend_dev_get(static_cast<size_t>(i)), &free_bytes, &total_bytes);
    const jlong values[2] = {static_cast<jlong>(free_bytes), static_cast<jlong>(total_bytes)};
    jlongArray out = env->NewLongArray(2);
    env->SetLongArrayRegion(out, 0, 2, values);
    return out;
}

} // extern "C"
