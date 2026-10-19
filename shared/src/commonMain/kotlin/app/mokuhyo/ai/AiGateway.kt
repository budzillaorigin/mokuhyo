package app.mokuhyo.ai

import app.mokuhyo.speech.SentenceSplitter
import app.mokuhyo.speech.SentenceStream
import app.mokuhyo.speech.TurnStage
import app.mokuhyo.speech.currentTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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

    /**
     * Work nobody is waiting for (feedback, glosses): it runs only when no live reply is queued, and a live reply
     * preempts it (the call then ends as [AiResult.Unavailable] "deferred"). Live replies are foreground.
     */
    val background: Boolean get() = false

    fun messages(input: I): List<ChatMessage>

    /** Problems with [output]; empty means it may be shown. */
    fun validate(input: I, output: O, context: ValidationContext): List<String>

    /** A non-LLM answer, or null if this task has none. */
    fun fallback(input: I): O? = null
}

/**
 * A prompt whose spoken field can be streamed to speech sentence by sentence (BRIEF_INTERVIEW_SPEED N3).
 * [validateSentence] vets each complete sentence before it may be spoken; the whole reply is still validated at the end.
 */
interface StreamingPromptTask<I, O> : PromptTask<I, O> {
    /** The top-level JSON string field that is spoken ("utterance", "reply"). */
    val streamField: String

    /** Problem with [sentence] (null = it may be spoken); [spoken] are this reply's sentences already handed to speech. */
    fun validateSentence(input: I, sentence: String, spoken: List<String>): String?
}

/** A streamed run: the result, and the sentences already handed to speech (possibly playing) before it ended. */
class Streamed<out T>(val result: AiResult<T>, val spoken: List<String>)

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

/** One AI call as the app's log records it: task, engine, duration, outcome ("ok" / "fallback" / "unavailable"), reason. */
data class AiCall(val task: String, val engine: String?, val ms: Long, val outcome: String, val reason: String?)

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
class AiGateway private constructor(
    private val model: () -> LanguageModel?,
    private val settings: AiSettings,
    private val context: ValidationContext,
    private val onCall: (AiCall) -> Unit,
    /** One per engine: a [pinned] gateway shares it, so its calls still queue behind (and preempt) the others. */
    private val scheduler: InferenceScheduler,
) {
    constructor(
        /** The current model (local, endpoint, or null when none is set up). Read per call so settings changes apply. */
        model: () -> LanguageModel?,
        settings: AiSettings = AiSettings(),
        context: ValidationContext = ValidationContext(),
        /** Called once per [run] with what happened (BRIEF_PHASE8 N-00b: the app's rolling log). Never sees prompt text. */
        onCall: (AiCall) -> Unit = {},
    ) : this(model, settings, context, onCall, InferenceScheduler())

    /**
     * A gateway fixed to the model configured right now (BRIEF_INTERVIEW_SPEED N2): a spoken session keeps one
     * conversation model loaded for its whole length, so a settings change mid-interview never swaps models (and never
     * reloads gigabytes between two questions). It shares this gateway's scheduler.
     */
    fun pinned(): AiGateway {
        val fixed = model()
        return AiGateway({ fixed }, settings, context, onCall, scheduler)
    }

    /**
     * Loads the model and decodes [task]'s prompt for [input] with a one-token reply (N4 preflight), so the first live
     * question starts from a warm model and a cached system prompt (the engine reuses the longest shared prefix).
     * Returns null when warm, else why not; "no model" when none is set up.
     */
    suspend fun <I, O> warm(task: PromptTask<I, O>, input: I): String? {
        val lm = model() ?: return "no model"
        return scheduler.run(false, deferred = { "deferred" }) {
            try {
                withTimeout(settings.timeoutMs) {
                    lm.complete(CompletionRequest(withJsonContract(task.messages(input), task.schema), maxTokens = 1, temperature = 0.0))
                }
                null
            } catch (e: TimeoutCancellationException) {
                "the model took too long to load"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: "the model failed to load"
            }
        }
    }

    /** The pinned (or current) model's id, for labels and timelines. */
    fun modelId(): String? = model()?.id
    /** True when a model is configured right now (it may still fail on a given call). */
    fun hasModel(): Boolean = model() != null

    /** The configured model's context window, or [LocalLlamaModel.DEFAULT_CONTEXT] when unknown. */
    fun contextSize(): Int = model()?.contextSize ?: LocalLlamaModel.DEFAULT_CONTEXT

    @Throws(Exception::class)
    suspend fun <I, O> run(task: PromptTask<I, O>, input: I, timeoutMs: Long? = null, deadlineMs: Long? = null): AiResult<O> {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        currentTrace()?.mark(TurnStage.LLM_QUEUED)
        val result = withDeadline(task, input, deadlineMs, CompletableDeferred()) {
            scheduler.run(task.background, deferred = { AiResult.Unavailable("Feedback deferred for the current response") }) { runInner(task, input, timeoutMs) }
        }
        log(task, result, start.elapsedNow().inWholeMilliseconds)
        return result
    }

    private fun log(task: PromptTask<*, *>, result: AiResult<*>, ms: Long) {
        runCatching {
            onCall(when (result) {
                is AiResult.Ok -> AiCall(task.name, result.engine, ms, "ok", null)
                is AiResult.Fallback -> AiCall(task.name, model()?.id, ms, "fallback", result.reason)
                is AiResult.Unavailable -> AiCall(task.name, model()?.id, ms, "unavailable", result.reason)
            })
        }
    }

    /**
     * Like [run], but the reply's [StreamingPromptTask.streamField] goes to [onSentence] one complete, checked sentence
     * at a time while the model is still writing (N3). Never partial JSON, never a sentence still being generated, never
     * one that failed its check. A reply that fails after nothing was spoken is retried as usual; once a sentence has
     * gone to speech there is no retry: the run stops and falls back (the caller then plays a guided prompt).
     */
    @Throws(Exception::class)
    suspend fun <I, O> stream(
        task: StreamingPromptTask<I, O>, input: I, splitter: SentenceSplitter, onSentence: suspend (String) -> Unit, timeoutMs: Long? = null,
        /**
         * The whole-turn deadline (N3): queue wait, generation and retries until the first sentence is ready to speak.
         * Missed → generation stops and the result is the task's fallback with [DEADLINE_REASON]. Once a sentence has
         * gone to speech only [timeoutMs] (the hard ceiling) still applies. Null = no deadline.
         */
        deadlineMs: Long? = null,
    ): Streamed<O> {
        val start = kotlin.time.TimeSource.Monotonic.markNow()
        currentTrace()?.mark(TurnStage.LLM_QUEUED)
        val spoken = ArrayList<String>()
        val first = CompletableDeferred<Unit>()
        val result = withDeadline(task, input, deadlineMs, first) {
            scheduler.run(task.background, deferred = { AiResult.Unavailable("Feedback deferred for the current response") }) {
                streamInner(task, input, splitter, { s -> first.complete(Unit); onSentence(s) }, spoken, timeoutMs)
            }
        }
        log(task, result, start.elapsedNow().inWholeMilliseconds)
        return Streamed(result, spoken.toList())
    }

    /**
     * Runs [work], but gives up after [deadlineMs] unless [started] (or the work itself) completed by then: the work is
     * cancelled (and waited for, so the engine has stopped) and the task's fallback answers with [DEADLINE_REASON]. A
     * race rather than a timeout around [work], whose own per-attempt timeout handling would swallow it.
     */
    private suspend fun <I, O> withDeadline(task: PromptTask<I, O>, input: I, deadlineMs: Long?, started: CompletableDeferred<Unit>, work: suspend () -> AiResult<O>): AiResult<O> {
        if (deadlineMs == null) return work()
        return coroutineScope {
            val job = async { work() }
            job.invokeOnCompletion { started.complete(Unit) }
            if (withTimeoutOrNull(deadlineMs) { started.await() } == null) {
                job.cancel()
                job.join()
                fallbackOr(task, input, DEADLINE_REASON)
            } else job.await()
        }
    }

    private suspend fun <I, O> streamInner(
        task: StreamingPromptTask<I, O>, input: I, splitter: SentenceSplitter, onSentence: suspend (String) -> Unit,
        spoken: MutableList<String>, timeoutMs: Long?,
    ): AiResult<O> {
        val lm = model() ?: return fallbackOr(task, input, "no AI model is set up")
        var messages = withJsonContract(task.messages(input), task.schema)
        var lastProblem = ""
        val trace = currentTrace()
        val attempts = if (settings.retryInvalid) 2 else 1
        suspend fun hand(sentences: List<String>) = sentences.forEach { spoken += it; onSentence(it) }
        fun stopped(problem: String) = fallbackOr(task, input, "the model's answer failed checks after ${spoken.size} sentence(s) were spoken ($problem)")
        repeat(attempts) { attempt ->
            trace?.mark(TurnStage.LLM_START)
            trace?.timeline?.note(trace.turn) { llmAttempts++ }
            val sentences = SentenceStream(task.streamField, splitter) { s, prior -> task.validateSentence(input, s, prior) }
            val pieces = Channel<String>(Channel.UNLIMITED)
            var streamedAny = false
            val completion: CompletionResult? = try {
                withTimeout(timeoutMs ?: settings.timeoutMs) {
                    coroutineScope {
                        val request = CompletionRequest(
                            ContextWindow.fit(messages, lm.contextSize ?: LocalLlamaModel.DEFAULT_CONTEXT, task.maxTokens),
                            maxTokens = task.maxTokens, temperature = task.temperature, jsonSchema = task.schema,
                            onToken = { pieces.trySend(it) },
                        )
                        val generation = async { try { lm.complete(request) } finally { pieces.close() } }
                        for (piece in pieces) {
                            streamedAny = true
                            hand(sentences.feed(piece))
                            if (sentences.problem != null) break
                        }
                        if (sentences.problem != null) {
                            generation.cancel()
                            null
                        } else generation.await()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                return if (spoken.isEmpty()) fallbackOr(task, input, "the model took too long") else stopped("the model took too long")
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiCancelledException) {
                return fallbackOr(task, input, CANCELLED_REASON)
            } catch (e: Exception) {
                return if (spoken.isEmpty()) fallbackOr(task, input, e.message ?: "the model failed") else stopped(e.message ?: "the model failed")
            }
            val text = completion?.text
            val problems = if (completion == null || text == null) listOf(sentences.problem!!) else {
                when (val parsed = parse(task, text)) {
                    null -> listOf("output was not valid JSON matching the schema")
                    else -> task.validate(input, parsed, context).ifEmpty {
                        // The whole reply holds up: speak the rest of it (its last sentence ends with the field). An
                        // engine that doesn't stream (an endpoint) hands over the whole text here, still sentence by sentence.
                        if (!streamedAny) hand(sentences.feed(text))
                        hand(sentences.finish())
                        sentences.problem?.let { return stopped(it) }
                        trace?.mark(TurnStage.LLM_VALIDATED)
                        return AiResult.Ok(parsed, completion.engine)
                    }
                }
            }
            lastProblem = problems.joinToString("; ")
            if (spoken.isNotEmpty()) return stopped(lastProblem)
            if (attempt == 0) {
                messages = messages + listOfNotNull(text?.let { ChatMessage(Role.ASSISTANT, it) }) + ChatMessage(
                    Role.USER,
                    "That answer had problems: $lastProblem. Reply again with only the corrected JSON object.",
                )
            }
        }
        return fallbackOr(task, input, "the model's answer failed checks ($lastProblem)")
    }

    private suspend fun <I, O> runInner(task: PromptTask<I, O>, input: I, timeoutMs: Long?): AiResult<O> {
        val lm = model() ?: return fallbackOr(task, input, "no AI model is set up")
        val base = task.messages(input)
        var messages = withJsonContract(base, task.schema)
        var lastProblem = ""
        val attempts = if (settings.retryInvalid) 2 else 1
        val trace = currentTrace()
        repeat(attempts) { attempt ->
            trace?.mark(TurnStage.LLM_START)
            trace?.timeline?.note(trace.turn) { llmAttempts++ }
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
            if (parsed != null && problems.isEmpty()) {
                trace?.mark(TurnStage.LLM_VALIDATED)
                return AiResult.Ok(parsed, result.engine)
            }
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

        /** [AiResult.Fallback.reason] when a live reply missed the whole-turn deadline (N3). */
        const val DEADLINE_REASON = "the reply missed its deadline"

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
