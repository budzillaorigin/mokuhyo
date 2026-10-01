// Shared helpers for Mokuhyo's JNI glue (llama_jni.cpp, whisper_jni.cpp, ggml_jni.cpp).
#pragma once

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <string>

namespace mokuhyo {

/// UTF-8 bytes from Kotlin (`String.toByteArray(UTF_8)`). JNI's modified UTF-8 (GetStringUTFChars) mangles
/// characters outside the BMP, so text always crosses the boundary as byte arrays.
inline std::string to_string(JNIEnv *env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize n = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(n), '\0');
    env->GetByteArrayRegion(bytes, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

inline jbyteArray to_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr != nullptr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

/// Plain-ASCII error messages only (NewStringUTF takes modified UTF-8).
inline jstring error(JNIEnv *env, const std::string &message) { return env->NewStringUTF(message.c_str()); }

/// Per-bridge cancellation high-water mark (F-10): each call carries an increasing id; cancel() raises
/// `through` to the last issued id. Read lock-free from ggml's abort callbacks (any thread). Allocated once per
/// Kotlin bridge and never freed (8 bytes), so a late cancel can never touch freed memory.
struct CancelToken {
    std::atomic<int64_t> through{0};
};

/// What an abort callback needs: the token and the id of the call in flight.
struct AbortState {
    CancelToken *token = nullptr;
    int64_t id = 0;

    bool cancelled() const { return token != nullptr && token->through.load(std::memory_order_relaxed) >= id; }
};

/// ggml_abort_callback signature: returns true to abort the running computation.
inline bool abort_if_cancelled(void *data) { return static_cast<const AbortState *>(data)->cancelled(); }

} // namespace mokuhyo
