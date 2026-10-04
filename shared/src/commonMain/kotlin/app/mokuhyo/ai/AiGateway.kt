package app.mokuhyo.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * One prompt in the library (BRIEF §7.2): how to phrase it, the output shape, the checks the output must pass,
 * and an optional deterministic fallback (e.g. the grammar pack's own explanation) used when no model is set up
 * or the model keeps failing.
 */
interface PromptTask<I, O> {
    val name: String
    val schema: JsonSchema
    val serializer: KSerializer<O>
    val maxTokens: Int get() = 512
    val temperature: Double get() = 0.3

    fun messages(input: I): List<ChatMessage>

    /** Problems with [output]; empty means it may be shown. */
    fun validate(input: I, output: O, context: ValidationContext): List<String>

    /** A non-LLM answer, or null if this task has none. */
    fun fallback(input: I): O? = null
}

sealed interface AiResult<out T> {
    /** Model output that passed validation. [engine] is shown next to it with the "AI-generated" badge. */
    data class Ok<T>(val value: T, val engine: String) : AiResult<T>

    /** Deterministic, non-LLM answer used because [reason] (no model, timeout, output failed validation). */
    data class Fallback<T>(val value: T, val reason: String) : AiResult<T>

    /** Nothing to show; the UI explains [reason] and offers model download / endpoint setup. */
    data class Unavailable(val reason: String) : AiResult<Nothing>
}

fun <T> AiResult<T>.valueOrNull(): T? = when (this) {
    is AiResult.Ok -> value
    is AiResult.Fallback -> value
    is AiResult.Unavailable -> null
}

data class AiSettings(
    val timeoutMs: Long = 90_000,
    /** Retry once when the output isn't valid JSON of the right shape or fails validation. */
    val retryInvalid: Boolean = true,
)

/**
 * The single entry point for AI tasks. It picks the configured model, adds the JSON contract to the prompt,
 * enforces a timeout, parses and validates the output, retries once with the problems spelled out, and falls back
 * deterministically. It never throws for engine problems; callers get an [AiResult].
 */
class AiGateway(
    /** The current model (local, endpoint, or null when none is set up). Read per call so settings changes apply. */
    private val model: () -> LanguageModel?,
    private val settings: AiSettings = AiSettings(),
    private val context: ValidationContext = ValidationContext(),
) {
    /** True when a model is configured right now (it may still fail on a given call). */
    fun hasModel(): Boolean = model() != null

    /** The configured model's context window, or [LocalLlamaModel.DEFAULT_CONTEXT] when unknown. */
    fun contextSize(): Int = model()?.contextSize ?: LocalLlamaModel.DEFAULT_CONTEXT

    @Throws(Exception::class)
    suspend fun <I, O> run(task: PromptTask<I, O>, input: I, timeoutMs: Long? = null): AiResult<O> {
        val lm = model() ?: return fallbackOr(task, input, "no AI model is set up")
        val base = task.messages(input)
        var messages = withJsonContract(base, task.schema)
        var lastProblem = ""
        val attempts = if (settings.retryInvalid) 2 else 1
        repeat(attempts) { attempt ->
            val result = try {
                withTimeout(timeoutMs ?: settings.timeoutMs) {
                    lm.complete(
                        CompletionRequest(
                            ContextWindow.fit(messages, lm.contextSize ?: LocalLlamaModel.DEFAULT_CONTEXT, task.maxTokens),
                            maxTokens = task.maxTokens, temperature = task.temperature, jsonSchema = task.schema,
                        ),
                    )
                }
            } catch (e: TimeoutCancellationException) {
                return fallbackOr(task, input, "the model took too long")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiCancelledException) {
                // Stopped on purpose (unload, superseded): partial output isn't an answer and a retry would just
                // reload the model right after a memory warning (F-10). Never retried.
                return fallbackOr(task, input, CANCELLED_REASON)
            } catch (e: Exception) {
                return fallbackOr(task, input, e.message ?: "the model failed")
            }
            val parsed = parse(task, result.text)
            val problems = when (parsed) {
                null -> listOf("output was not valid JSON matching the schema")
                else -> task.validate(input, parsed, context)
            }
            if (parsed != null && problems.isEmpty()) return AiResult.Ok(parsed, result.engine)
            lastProblem = problems.joinToString("; ")
            if (attempt == 0) {
                messages = messages + ChatMessage(Role.ASSISTANT, result.text) + ChatMessage(
                    Role.USER,
                    "That answer had problems: $lastProblem. Reply again with only the corrected JSON object.",
                )
            }
        }
        return fallbackOr(task, input, "the model's answer failed checks ($lastProblem)")
    }

    private fun <I, O> fallbackOr(task: PromptTask<I, O>, input: I, reason: String): AiResult<O> =
        task.fallback(input)?.let { AiResult.Fallback(it, reason) } ?: AiResult.Unavailable(reason)

    private fun <O> parse(task: PromptTask<*, O>, text: String): O? {
        val jsonText = extractJsonObject(text) ?: return null
        return try {
            lenient.decodeFromString(task.serializer, jsonText)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /** [AiResult.Fallback.reason] / [AiResult.Unavailable.reason] when the engine reported cancellation. */
        const val CANCELLED_REASON = "the model was stopped"

        private val lenient = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }

        /** Endpoints in prompt-only mode may wrap JSON in prose or ``` fences; take the outermost object. */
        fun extractJsonObject(text: String): String? {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val c = text[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> if (--depth == 0) return text.substring(start, i + 1)
                }
            }
            return null
        }

        /** Appends the output contract to the system message so prompt-only endpoints still produce the shape. */
        fun withJsonContract(messages: List<ChatMessage>, schema: JsonSchema): List<ChatMessage> {
            val contract = "Respond with a single JSON object and nothing else. It must match this JSON Schema:\n" +
                schema.toJson().toString()
            val i = messages.indexOfFirst { it.role == Role.SYSTEM }
            return if (i < 0) {
                listOf(ChatMessage(Role.SYSTEM, contract)) + messages
            } else {
                messages.toMutableList().also { it[i] = ChatMessage(Role.SYSTEM, it[i].content + "\n\n" + contract) }
            }
        }
    }
}
