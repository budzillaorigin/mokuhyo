package app.mokuhyo.ai.jni

import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.LocalLlmBridge
import app.mokuhyo.ai.Role
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * On-device LLM inference with llama.cpp (MIT) through `mokuhyo_native`, behind the shared [LocalLlmBridge].
 *
 * - All native work runs on one dedicated daemon thread ("mokuhyo-llama"), never the caller's; calls are serialized
 *   and `onToken`/`onDone` fire on that thread.
 * - Prompt: [ChatMessage]s formatted with the model's own chat template (GGUF `tokenizer.chat_template`, applied by
 *   `llama_chat_apply_template`); ChatML when the model has none or llama.cpp doesn't recognise it (then
 *   `<|im_end|>` is also a stop string).
 * - Sampling: optional GBNF grammar → top-k 40 → repeat penalty 1.1 → top-p 0.9 → temperature → random draw with a
 *   fresh seed per call (temperature ≤ 0: greedy).
 * - KV-cache reuse: a prompt that extends the previous prompt + reply (a growing conversation) only decodes the new
 *   suffix.
 * - GPU: every layer is offloaded (n_gpu_layers = 999) when the Metal/Vulkan variant loaded with a GPU device, else 0.
 *   If a GPU load fails (e.g. out of VRAM) it retries once on the CPU and says so in [lastLoadNote].
 * - Cancellation (F-10): every [generate] gets an increasing id; [cancel] cancels every generation issued so far,
 *   queued or running, by raising a high-water mark (here and in native code, where it also aborts prompt decoding).
 *   A cancelled generation ends with `onDone(null, "cancelled")`, never with truncated text.
 */
class LlamaJniBridge(
    /** CPU threads; defaults to [NativeLibrary.recommendedThreads]. */
    private val threads: Int? = null,
) : LocalLlmBridge {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "mokuhyo-llama", 16L * 1024 * 1024).apply { isDaemon = true }
    }
    private val lastIssued = AtomicLong(0)
    private val cancelledThrough = AtomicLong(0)
    @Volatile private var handle = 0L
    @Volatile private var cancelToken = 0L

    /** How the current model was loaded ("Metal, 999 GPU layers", "CPU after GPU load failed", …); null if none. */
    @Volatile var lastLoadNote: String? = null
        private set

    override fun isLoaded(): Boolean = handle != 0L

    override fun load(modelPath: String, contextSize: Int, onDone: (String?) -> Unit) {
        worker.execute {
            val error = try {
                loadNow(modelPath, contextSize)
            } catch (e: Throwable) {
                "Couldn't load the model: ${e.message ?: e.javaClass.simpleName}"
            }
            onDone(error)
        }
    }

    private fun loadNow(modelPath: String, contextSize: Int): String? {
        val status = NativeLibrary.load()
        if (!status.isLoaded) return "On-device AI library isn't available (${status.error})"
        ensureToken()
        free()
        if (!File(modelPath).isFile) return "Model file not found: $modelPath"
        val nThreads = threads ?: NativeLibrary.recommendedThreads()
        val nCtx = contextSize.coerceAtLeast(512)
        val path = modelPath.toByteArray(Charsets.UTF_8)
        val gpuLayers = if (status.gpuActive) GPU_ALL_LAYERS else 0
        var h = LlamaNative.nativeLoad(path, nCtx, nThreads, gpuLayers)
        var note = if (gpuLayers > 0) "${status.variant} GPU, all layers" else "CPU, $nThreads threads"
        if (h == 0L && gpuLayers > 0) {
            h = LlamaNative.nativeLoad(path, nCtx, nThreads, 0)
            note = "CPU, $nThreads threads (GPU load failed, likely not enough GPU memory)"
        }
        handle = h
        lastLoadNote = if (h == 0L) null else note
        return if (h == 0L) "Couldn't load the model (unsupported file or not enough memory for $nCtx tokens)" else null
    }

    override fun generate(
        messages: List<ChatMessage>,
        grammar: String?,
        maxTokens: Int,
        temperature: Double,
        stop: List<String>,
        onToken: (String) -> Unit,
        onDone: (String?, String?) -> Unit,
    ) {
        val id = lastIssued.incrementAndGet()
        worker.execute {
            val (text, error) = try {
                generateNow(id, messages, grammar, maxTokens, temperature, stop, onToken)
            } catch (e: Throwable) {
                if (isCancelled(id)) null to LocalLlmBridge.CANCELLED
                else null to "Generation failed: ${e.message ?: e.javaClass.simpleName}"
            }
            onDone(text, error)
        }
    }

    private fun isCancelled(id: Long) = cancelledThrough.get() >= id

    private fun generateNow(
        id: Long,
        messages: List<ChatMessage>,
        grammar: String?,
        maxTokens: Int,
        temperature: Double,
        stop: List<String>,
        onToken: (String) -> Unit,
    ): Pair<String?, String?> {
        if (isCancelled(id)) return null to LocalLlmBridge.CANCELLED
        val h = handle
        if (h == 0L) return null to "No model loaded"
        val templated = LlamaNative.nativeApplyChatTemplate(
            h,
            IntArray(messages.size) { roleCode(messages[it].role) },
            Array(messages.size) { messages[it].content.toByteArray(Charsets.UTF_8) },
        )
        val prompt = templated ?: chatMl(messages).toByteArray(Charsets.UTF_8)
        val matcher = StopMatcher(if (templated == null) stop + IM_END else stop)
        var stoppedByCancel = false
        var callbackError: Throwable? = null
        val sink = LlamaNative.Sink { bytes ->
            when {
                isCancelled(id) -> {
                    stoppedByCancel = true
                    false
                }
                else -> {
                    val ready = matcher.append(String(bytes, Charsets.UTF_8))
                    try {
                        if (ready.isNotEmpty()) onToken(ready)
                        !matcher.stopped
                    } catch (e: Throwable) {
                        callbackError = e
                        false
                    }
                }
            }
        }
        val error = LlamaNative.nativeGenerate(
            h,
            prompt,
            grammar?.takeIf { it.isNotBlank() }?.toByteArray(Charsets.UTF_8),
            maxTokens.coerceAtLeast(1),
            temperature.toFloat(),
            sink,
            cancelToken,
            id,
        )
        if (stoppedByCancel || isCancelled(id) || LocalLlmBridge.isCancelled(error)) return null to LocalLlmBridge.CANCELLED
        callbackError?.let { return null to "onToken failed: ${it.message ?: it.javaClass.simpleName}" }
        if (error != null) return null to error
        val tail = matcher.finish()
        if (tail.isNotEmpty()) onToken(tail)
        return matcher.text to null
    }

    /**
     * Cancels every generation issued so far; a running one stops at its next token (or mid prompt decode), a queued
     * one before it starts.
     */
    override fun cancel() {
        val issued = lastIssued.get()
        cancelledThrough.accumulateAndGet(issued) { a, b -> maxOf(a, b) }
        val token = cancelToken
        if (token != 0L) LlamaNative.nativeCancelThrough(token, issued)
    }

    override fun unload() {
        cancel()
        worker.execute { free() }
    }

    private fun ensureToken() {
        if (cancelToken == 0L) cancelToken = LlamaNative.nativeNewCancelToken()
    }

    private fun free() {
        val h = handle
        handle = 0L
        lastLoadNote = null
        if (h != 0L) LlamaNative.nativeFree(h)
    }

    companion object {
        /** "Offload every layer": llama.cpp clamps it to the model's layer count. */
        const val GPU_ALL_LAYERS = 999
        const val IM_START = "<|im_start|>"
        const val IM_END = "<|im_end|>"

        private fun roleCode(role: Role): Int = when (role) {
            Role.SYSTEM -> 0
            Role.USER -> 1
            Role.ASSISTANT -> 2
        }

        /** ChatML fallback: each turn wrapped in im_start/im_end, ending with an open assistant turn. */
        fun chatMl(messages: List<ChatMessage>): String = buildString {
            for (m in messages) {
                append(IM_START).append(m.role.name.lowercase()).append('\n').append(m.content).append(IM_END).append('\n')
            }
            append(IM_START).append("assistant\n")
        }
    }
}
