package app.mokuhyo.ai.jni

import app.mokuhyo.ai.CancellableSttBridge
import app.mokuhyo.ai.LocalLlmBridge
import app.mokuhyo.ai.LocalSttBridge
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device speech-to-text with whisper.cpp (MIT) through `mokuhyo_native`, behind [LocalSttBridge].
 *
 * Input: 16 kHz mono float samples in [-1, 1]. Output: `[{"t0": ms, "t1": ms, "text": "…"}]`. Work runs on one
 * dedicated daemon thread ("mokuhyo-whisper"), where `onDone` also fires. Uses the GPU (Metal/Vulkan) when the GPU
 * variant loaded with a device. [language] is a whisper code ("ja", "es", "zh", …); a BCP-47 tag like "zh-Hans" or
 * "pt-BR" is reduced to its primary subtag.
 *
 * Cancellation follows [LlamaJniBridge]: per-call ids and a high-water mark; whisper's abort callback stops the
 * running transcription and `onDone(null, "cancelled")` is reported.
 */
class WhisperJniBridge(
    /** CPU threads; defaults to [NativeLibrary.recommendedThreads]. */
    private val threads: Int? = null,
) : LocalSttBridge, CancellableSttBridge {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "mokuhyo-whisper", 16L * 1024 * 1024).apply { isDaemon = true }
    }
    private val lastIssued = AtomicLong(0)
    private val cancelledThrough = AtomicLong(0)
    @Volatile private var handle = 0L
    @Volatile private var cancelToken = 0L

    override fun isLoaded(): Boolean = handle != 0L

    override fun load(modelPath: String, onDone: (String?) -> Unit) {
        worker.execute {
            val error = try {
                loadNow(modelPath)
            } catch (e: Throwable) {
                "Couldn't load the speech model: ${e.message ?: e.javaClass.simpleName}"
            }
            onDone(error)
        }
    }

    private fun loadNow(modelPath: String): String? {
        val status = NativeLibrary.load()
        if (!status.isLoaded) return "On-device AI library isn't available (${status.error})"
        if (cancelToken == 0L) cancelToken = WhisperNative.nativeNewCancelToken()
        free()
        if (!File(modelPath).isFile) return "Speech model not found: $modelPath"
        val path = modelPath.toByteArray(Charsets.UTF_8)
        var h = WhisperNative.nativeInit(path, status.gpuActive)
        if (h == 0L && status.gpuActive) h = WhisperNative.nativeInit(path, false)
        handle = h
        return if (h == 0L) "Couldn't load the speech model at $modelPath" else null
    }

    override fun transcribe(samples: FloatArray, language: String, onDone: (String?, String?) -> Unit) {
        val id = lastIssued.incrementAndGet()
        worker.execute {
            val (json, error) = try {
                transcribeNow(id, samples, language)
            } catch (e: Throwable) {
                if (isCancelled(id)) null to LocalLlmBridge.CANCELLED
                else null to "Transcription failed: ${e.message ?: e.javaClass.simpleName}"
            }
            onDone(json, error)
        }
    }

    private fun isCancelled(id: Long) = cancelledThrough.get() >= id

    private fun transcribeNow(id: Long, samples: FloatArray, language: String): Pair<String?, String?> {
        if (isCancelled(id)) return null to LocalLlmBridge.CANCELLED
        val h = handle
        if (h == 0L) return null to "No speech model loaded"
        val lang = whisperLanguage(language)
        if (!WhisperNative.nativeIsLanguage(lang)) return null to "Whisper doesn't support language '$language'"
        if (samples.isEmpty()) return "[]" to null
        val n = WhisperNative.nativeTranscribe(h, samples, lang, threads ?: NativeLibrary.recommendedThreads(), cancelToken, id)
        if (n == WhisperNative.CANCELLED || isCancelled(id)) return null to LocalLlmBridge.CANCELLED
        if (n < 0) return null to "Transcription failed ($n)"
        val segments = buildJsonArray {
            for (i in 0 until n) {
                val times = WhisperNative.nativeSegmentTimes(h, i)
                addJsonObject {
                    put("t0", times[0])
                    put("t1", times[1])
                    put("text", String(WhisperNative.nativeSegmentText(h, i), Charsets.UTF_8).trim())
                }
            }
        }
        return segments.toString() to null
    }

    /** Stops the running transcription and every queued one; each reports `onDone(null, "cancelled")`. */
    override fun cancel() {
        val issued = lastIssued.get()
        cancelledThrough.accumulateAndGet(issued) { a, b -> maxOf(a, b) }
        val token = cancelToken
        if (token != 0L) WhisperNative.nativeCancelThrough(token, issued)
    }

    /** Cancels any work and frees the model (on the worker thread). */
    fun unload() {
        cancel()
        worker.execute { free() }
    }

    private fun free() {
        val h = handle
        handle = 0L
        if (h != 0L) WhisperNative.nativeFree(h)
    }

    companion object {
        /** "zh-Hans" → "zh", "pt-BR" → "pt", "JA" → "ja"; "auto" stays. */
        fun whisperLanguage(tag: String): String =
            tag.trim().substringBefore('-').substringBefore('_').lowercase(Locale.ROOT).ifEmpty { "auto" }
    }
}
