package app.mokuhyo.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native local inference provided by the apps (llama.cpp via Swift on iOS, JNI on Android). Callback-based so
 * Swift can implement it. Callbacks may arrive on any thread.
 *
 * - [load]: `onDone(null)` on success, `onDone(errorMessage)` on failure.
 * - [generate]: [prompt] is the fully formatted chat prompt (ChatML); [grammar] is a GBNF grammar or null.
 *   `onToken` streams pieces; `onDone(fullText, null)` on success, `onDone(null, errorMessage)` on failure.
 *   Generation stops at any of [stop] (the stop string itself is not included in the text).
 * - [cancel]: stop the running generation; the bridge then calls `onDone(null, "cancelled")`.
 *
 * **Cancellation contract (F-10, tools/models/README.md):** whenever a generation (or transcription) ends because
 * it was stopped — [cancel], [unload] during generation, a newer call superseding it — the bridge MUST call
 * `onDone(null, error)` with `error` starting with [CANCELLED] (e.g. `"cancelled"` or `"cancelled: unloaded"`),
 * and MUST NOT return the partial text as a success. Shared code maps that to [AiCancelledException], which the
 * gateway never retries.
 */
interface LocalLlmBridge {
    fun isLoaded(): Boolean
    fun load(modelPath: String, contextSize: Int, onDone: (String?) -> Unit)
    fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        temperature: Double,
        stop: List<String>,
        onToken: (String) -> Unit,
        onDone: (String?, String?) -> Unit,
    )
    fun cancel()
    fun unload()

    companion object {
        /** Error prefix a bridge reports when a call was stopped rather than failed. */
        const val CANCELLED = "cancelled"

        fun isCancelled(error: String?): Boolean = error != null && error.trim().startsWith(CANCELLED, ignoreCase = true)
    }
}

/**
 * Which model file the native bridge currently holds. One per bridge (the apps have one llama context), shared by
 * every [LocalLlamaModel] built on it, so switching models in settings reloads instead of silently generating with
 * the old weights (F-23).
 */
class LoadedModelSlot {
    internal val lock = Mutex()
    var path: String? = null
        internal set

    /** Unloads whatever the bridge holds and forgets the path (settings change, memory warning). */
    suspend fun unload(bridge: LocalLlmBridge) = lock.withLock {
        if (bridge.isLoaded()) bridge.unload()
        path = null
    }
}

/**
 * On-device model over [LocalLlmBridge]. Formats a ChatML prompt and turns [CompletionRequest.jsonSchema]
 * into a GBNF grammar so output is always valid JSON of the right shape (BRIEF §7.1).
 */
class LocalLlamaModel(
    private val bridge: LocalLlmBridge,
    private val modelInfo: ModelInfo,
    /** Absolute path of the model's first file (see [ModelManager.modelPath]). */
    private val modelPath: String? = null,
    private val slot: LoadedModelSlot = LoadedModelSlot(),
) : LanguageModel {
    override val id: String = modelInfo.id
    override val isLocal: Boolean = true
    override val contextSize: Int = modelInfo.contextSize.takeIf { it > 0 } ?: DEFAULT_CONTEXT
    private val engineLabel = "on-device ${modelInfo.name}"

    @Throws(Exception::class)
    override suspend fun complete(request: CompletionRequest): CompletionResult {
        when {
            modelPath != null -> ensureLoaded(modelPath)
            // No file known (the host app loaded it itself): use whatever is loaded, or fail honestly.
            !bridge.isLoaded() -> throw AiException("model ${modelInfo.id} is not downloaded")
        }
        val prompt = chatMl(request.messages)
        val grammar = request.jsonSchema?.toGbnf()
        val stop = (request.stop + IM_END).distinct()
        val text = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { bridge.cancel() }
            bridge.generate(prompt, grammar, request.maxTokens, request.temperature, stop, onToken = {}) { full, error ->
                if (!cont.isActive) return@generate
                when {
                    full != null -> cont.resume(full)
                    LocalLlmBridge.isCancelled(error) -> cont.resumeWithException(AiCancelledException(error ?: LocalLlmBridge.CANCELLED))
                    else -> cont.resumeWithException(AiException(error ?: "generation failed"))
                }
            }
        }
        return CompletionResult(text.removeSuffix(IM_END).trim(), engineLabel, null)
    }

    /**
     * Makes sure the bridge holds [path]: no-op when it already does, otherwise unloads whatever is loaded (another
     * model, or one loaded outside this slot) and loads [path].
     */
    @Throws(Exception::class)
    suspend fun ensureLoaded(path: String) = slot.lock.withLock {
        if (bridge.isLoaded() && slot.path == path) return@withLock
        if (bridge.isLoaded()) bridge.unload()
        slot.path = null
        suspendCancellableCoroutine { cont ->
            bridge.load(path, contextSize) { error ->
                if (!cont.isActive) return@load
                if (error == null) cont.resume(Unit) else cont.resumeWithException(AiException("couldn't load model: $error"))
            }
        }
        slot.path = path
    }

    companion object {
        const val IM_START = "<|im_start|>"
        const val IM_END = "<|im_end|>"
        const val DEFAULT_CONTEXT = 4096

        /** ChatML: each turn wrapped in im_start/im_end, ending with an open assistant turn. */
        fun chatMl(messages: List<ChatMessage>): String = buildString {
            for (m in messages) {
                append(IM_START).append(m.role.name.lowercase()).append('\n').append(m.content).append(IM_END).append('\n')
            }
            append(IM_START).append("assistant\n")
        }
    }
}
