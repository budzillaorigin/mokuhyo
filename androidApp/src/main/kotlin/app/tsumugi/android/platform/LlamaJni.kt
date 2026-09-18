package app.tsumugi.android.platform

import android.content.Context
import app.tsumugi.ai.LocalLlmBridge
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device LLM inference with llama.cpp (MIT, CPU/NEON) behind the shared [LocalLlmBridge].
 *
 * - All native work runs on one dedicated thread, so calls are serialized; `onToken`/`onDone` fire on it.
 * - Sampling: optional GBNF grammar → top-k 40 → repeat penalty 1.1 → top-p 0.9 → temperature → random draw.
 * - KV-cache reuse: a prompt that extends the previous prompt + reply (a growing conversation) only decodes the
 *   new suffix.
 * - `libtsumugi_llama.so` loads lazily on the first [load], so constructing this is free.
 */
class LlamaJni(context: Context) : LocalLlmBridge {
    @Suppress("unused") private val appContext = context.applicationContext
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "tsumugi-llama", 16L * 1024 * 1024).apply { isDaemon = true }
    }
    private val cancelled = AtomicBoolean(false)
    @Volatile private var handle = 0L

    override fun isLoaded(): Boolean = handle != 0L

    override fun load(modelPath: String, contextSize: Int, onDone: (String?) -> Unit) {
        worker.execute {
            NativeLibs.check("tsumugi_llama")?.let { onDone(it); return@execute }
            free()
            if (!File(modelPath).isFile) { onDone("Model file not found: $modelPath"); return@execute }
            val h = LlamaNative.nativeLoad(modelPath, contextSize.coerceAtLeast(512), NativeLibs.threads())
            handle = h
            onDone(if (h == 0L) "Couldn't load the model (unsupported file or not enough memory for $contextSize tokens)" else null)
        }
    }

    override fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        temperature: Double,
        stop: List<String>,
        onToken: (String) -> Unit,
        onDone: (String?, String?) -> Unit,
    ) {
        cancelled.set(false)
        worker.execute {
            val h = handle
            if (h == 0L) { onDone(null, "No model loaded"); return@execute }
            val text = StringBuilder()
            val sink = object : LlamaNative.Sink {
                override fun onPiece(bytes: ByteArray): Boolean {
                    if (cancelled.get()) return false
                    text.append(String(bytes, Charsets.UTF_8))
                    val cut = stop.mapNotNull { s -> text.indexOf(s).takeIf { it >= 0 } }.minOrNull()
                    if (cut != null) {
                        text.setLength(cut)
                        return false
                    }
                    onToken(String(bytes, Charsets.UTF_8))
                    return true
                }
            }
            val error = LlamaNative.nativeGenerate(
                h,
                prompt.toByteArray(Charsets.UTF_8),
                grammar?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8),
                maxTokens,
                temperature.toFloat(),
                sink,
            )
            onDone(if (error != null && text.isEmpty()) null else text.toString(), error)
        }
    }

    /** Stops generation at the next token (checked in the sink, so no native call races with [unload]). */
    override fun cancel() = cancelled.set(true)

    override fun unload() {
        cancel()
        worker.execute { free() }
    }

    private fun free() {
        val h = handle
        handle = 0L
        if (h != 0L) LlamaNative.nativeFree(h)
    }
}

/** JNI entry points in `src/main/cpp/llama_jni.cpp`. */
internal object LlamaNative {
    interface Sink {
        /** A complete UTF-8 chunk of output; return false to stop generating. */
        fun onPiece(bytes: ByteArray): Boolean
    }

    @JvmStatic external fun nativeLoad(path: String, nCtx: Int, nThreads: Int): Long
    @JvmStatic external fun nativeFree(handle: Long)
    /** Returns null on success, else an error message. */
    @JvmStatic external fun nativeGenerate(
        handle: Long, prompt: ByteArray, grammar: ByteArray?, maxTokens: Int, temperature: Float, sink: Sink,
    ): String?
}

/** Lazy, once-per-library `System.loadLibrary` with a CPU capability check. */
internal object NativeLibs {
    private val loaded = HashMap<String, String?>()

    /** Loads `lib<name>.so` if needed; returns null when usable, else a message for the user. */
    @Synchronized
    fun check(name: String): String? {
        if (name in loaded) return loaded[name]
        val result = when {
            !cpuHasDotProd() ->
                "This device's CPU is too old for on-device AI (needs ARMv8.2 dot-product instructions)"
            else -> try {
                System.loadLibrary(name)
                null
            } catch (e: UnsatisfiedLinkError) {
                "On-device AI isn't included in this build (${e.message})"
            }
        }
        loaded[name] = result
        return result
    }

    /** Big cores only: using every core makes the little ones the bottleneck. */
    fun threads(): Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

    private fun cpuHasDotProd(): Boolean = try {
        File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") }
            .let { lines -> lines.isEmpty() || lines.all { "asimddp" in it.split(' ', '\t') } }
    } catch (_: Exception) {
        true // unreadable: assume a modern CPU rather than blocking the feature
    }
}
