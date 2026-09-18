package app.tsumugi.ai

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
}

/**
 * On-device model over [LocalLlmBridge]. Formats Qwen's ChatML template and turns [CompletionRequest.jsonSchema]
 * into a GBNF grammar so output is always valid JSON of the right shape (BRIEF §7.1).
 */
class LocalLlamaModel(
    private val bridge: LocalLlmBridge,
    private val modelInfo: ModelInfo,
    /** Absolute path of the model's first file (see [ModelManager.modelPath]). */
    private val modelPath: String? = null,
) : LanguageModel {
    override val id: String = modelInfo.id
    override val isLocal: Boolean = true
    private val engineLabel = "on-device ${modelInfo.name}"

    override suspend fun complete(request: CompletionRequest): CompletionResult {
        ensureLoaded()
        val prompt = chatMl(request.messages)
        val grammar = request.jsonSchema?.toGbnf()
        val stop = (request.stop + IM_END).distinct()
        val text = suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { bridge.cancel() }
            bridge.generate(prompt, grammar, request.maxTokens, request.temperature, stop, onToken = {}) { full, error ->
                if (!cont.isActive) return@generate
                if (full != null) cont.resume(full) else cont.resumeWithException(AiException(error ?: "generation failed"))
            }
        }
        return CompletionResult(text.removeSuffix(IM_END).trim(), engineLabel, null)
    }

    private suspend fun ensureLoaded() {
        if (bridge.isLoaded()) return
        val path = modelPath ?: throw AiException("model ${modelInfo.id} is not downloaded")
        suspendCancellableCoroutine { cont ->
            bridge.load(path, modelInfo.contextSize.takeIf { it > 0 } ?: DEFAULT_CONTEXT) { error ->
                if (!cont.isActive) return@load
                if (error == null) cont.resume(Unit) else cont.resumeWithException(AiException("couldn't load model: $error"))
            }
        }
    }

    companion object {
        const val IM_START = "<|im_start|>"
        const val IM_END = "<|im_end|>"
        const val DEFAULT_CONTEXT = 4096

        /** Qwen2.5 ChatML: each turn wrapped in im_start/im_end, ending with an open assistant turn. */
        fun chatMl(messages: List<ChatMessage>): String = buildString {
            for (m in messages) {
                append(IM_START).append(m.role.name.lowercase()).append('\n').append(m.content).append(IM_END).append('\n')
            }
            append(IM_START).append("assistant\n")
        }
    }
}
