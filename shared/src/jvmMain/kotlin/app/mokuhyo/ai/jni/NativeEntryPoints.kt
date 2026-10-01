package app.mokuhyo.ai.jni

// JNI entry points of native/cpp/*.cpp. Kept in internal objects with public members: Kotlin mangles the JVM names
// of `internal` members, which would break the `Java_app_mokuhyo_ai_jni_<Class>_<method>` symbols.
// Text crosses the boundary as UTF-8 byte arrays (JNI's modified UTF-8 mangles characters outside the BMP).

/** native/cpp/ggml_jni.cpp: variant, cores, ggml backend device registry. */
internal object GgmlNative {
    /** "cpu", "metal" or "vulkan": what the loaded binary was built as. */
    @JvmStatic external fun nativeVariant(): String

    /** Apple performance cores / physical cores where the OS reports them, else 0. */
    @JvmStatic external fun nativePerformanceCores(): Int

    @JvmStatic external fun nativeDeviceCount(): Int

    /** `{name, description, registry name, type}` with type ∈ cpu, gpu, igpu, accel. */
    @JvmStatic external fun nativeDeviceInfo(index: Int): Array<String>?

    /** `{free, total}` bytes. */
    @JvmStatic external fun nativeDeviceMemory(index: Int): LongArray?
}

/** native/cpp/llama_jni.cpp. */
internal object LlamaNative {
    fun interface Sink {
        /** A complete UTF-8 chunk of output; return false to stop generating. */
        fun onPiece(bytes: ByteArray): Boolean
    }

    /** A per-bridge cancellation high-water mark (never freed). */
    @JvmStatic external fun nativeNewCancelToken(): Long

    /** Cancels every call on [token] with id ≤ [through]. Any thread. */
    @JvmStatic external fun nativeCancelThrough(token: Long, through: Long)

    /** Returns a session handle, or 0 on failure. */
    @JvmStatic external fun nativeLoad(path: ByteArray, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long

    @JvmStatic external fun nativeFree(handle: Long)

    @JvmStatic external fun nativeContextSize(handle: Long): Int

    /** The chat formatted with the model's template (UTF-8), or null when it has none llama.cpp can apply. */
    @JvmStatic external fun nativeApplyChatTemplate(handle: Long, roles: IntArray, contents: Array<ByteArray>): ByteArray?

    /** Returns null on success, else an error message ("cancelled" when aborted through [token]/[id]). */
    @JvmStatic external fun nativeGenerate(
        handle: Long, prompt: ByteArray, grammar: ByteArray?, maxTokens: Int, temperature: Float, sink: Sink,
        token: Long, id: Long,
    ): String?

    @JvmStatic external fun nativeSystemInfo(): String
}

/** native/cpp/whisper_jni.cpp. */
internal object WhisperNative {
    const val CANCELLED = -1000

    @JvmStatic external fun nativeNewCancelToken(): Long

    @JvmStatic external fun nativeCancelThrough(token: Long, through: Long)

    /** Returns a session handle, or 0 on failure. */
    @JvmStatic external fun nativeInit(path: ByteArray, useGpu: Boolean): Long

    @JvmStatic external fun nativeFree(handle: Long)

    /** True for a language code whisper knows ("ja", "es", …) or "auto". */
    @JvmStatic external fun nativeIsLanguage(language: String): Boolean

    /** Number of segments, [CANCELLED], or another negative error code. */
    @JvmStatic external fun nativeTranscribe(
        handle: Long, samples: FloatArray, language: String, nThreads: Int, token: Long, id: Long,
    ): Int

    /** `[t0, t1]` in milliseconds. */
    @JvmStatic external fun nativeSegmentTimes(handle: Long, index: Int): LongArray

    /** UTF-8 bytes of the segment text. */
    @JvmStatic external fun nativeSegmentText(handle: Long, index: Int): ByteArray

    @JvmStatic external fun nativeSystemInfo(): String
}
