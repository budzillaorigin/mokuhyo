package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiResult
import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Role
import app.mokuhyo.ai.Validation
import app.mokuhyo.ai.ValidationContext
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.ScriptCheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How corrections surface in a speaking session (BRIEF_PHASE8 §B.5, C-11). The correction pipeline is the same in
 * [LIVE] and [AFTER_ACTION] — same prompts, same records — only the moment they are shown differs. [OFF] records
 * the transcript and recordings only and generates no analysis.
 */
enum class CorrectionsMode(val id: String, val title: String, val chip: String) {
    LIVE("live", "Live", "Live — feedback after each turn"),
    AFTER_ACTION("after_action", "After action", "After action — feedback at the end"),
    OFF("off", "Off", "Off — no feedback will be generated"),
    ;

    val records: Boolean get() = this != OFF
    val showsDuringSession: Boolean get() = this == LIVE

    companion object {
        fun of(id: String?): CorrectionsMode? = entries.firstOrNull { it.id == id }

        /** OPI test mode ignores the learner's choice and always runs as After action. */
        fun effective(chosen: CorrectionsMode, opiTest: Boolean): CorrectionsMode = if (opiTest) AFTER_ACTION else chosen
    }
}

/** The speaking activities that have their own default mode (Settings → Speaking). */
enum class SpeakingActivity(val id: String, val title: String, val defaultMode: CorrectionsMode) {
    OPI_PRACTICE("opi", "Interview practice", CorrectionsMode.AFTER_ACTION),
    TOPIC("topic", "Topic conversation", CorrectionsMode.LIVE),
    PERSONA("persona", "Persona and scenario practice", CorrectionsMode.LIVE),
}

/**
 * One learner turn's feedback, identical in Live and After-action (it is what `conversation_turn_feedback` stores).
 * [partnerReply] is the partner's next line for context.
 */
@Serializable
data class TurnFeedbackRecord(
    val turnIndex: Int,
    val learner: String,
    val corrected: String? = null,
    val changes: List<TopicTurn.Change> = emptyList(),
    val rewrite: String? = null,
    val vocabulary: List<TopicTurn.Vocab> = emptyList(),
    val turnLevel: String? = null,
    val pragmatics: List<PragmaticFlag> = emptyList(),
    val engine: String? = null,
    val partnerReply: String? = null,
) {
    companion object {
        fun of(turnIndex: Int, ex: TopicExchange): TurnFeedbackRecord = TurnFeedbackRecord(
            turnIndex, ex.learner, ex.corrected, ex.changes, ex.rewrite, ex.vocabulary, ex.turnLevel, ex.pragmatics, ex.engine, ex.reply,
        )
    }
}

private fun languageName(code: String) = Languages.of(code)?.nameEnglish ?: code

/**
 * `turn_feedback` (BRIEF_PHASE8 C-11): feedback on one interview answer — the same fields as a topic turn's
 * feedback (correction, natural rewrite, vocabulary, turn level, pragmatic flags), without a partner reply. Used for
 * interview practice, where the interviewer's question comes from `opi_interviewer_turn`.
 */
class TurnFeedback : PromptTask<TurnFeedback.Input, TurnFeedback.Output> {
    data class Input(val language: String, val registerNotes: String, val question: String, val answer: String, val culturalNotes: List<String> = emptyList())

    @Serializable
    data class Output(
        val corrected: String,
        val changes: List<TopicTurn.Change> = emptyList(),
        val rewrite: String,
        val vocabulary: List<TopicTurn.Vocab> = emptyList(),
        @SerialName("turn_level") val turnLevel: String,
        val pragmatics: List<PragmaticFlag> = emptyList(),
    )

    override val name = "turn_feedback"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.3
    override val maxTokens = 800
    override val schema = JsonSchema.Obj(listOf(
        "corrected" to JsonSchema.Str(maxLength = 600),
        "changes" to JsonSchema.Arr(JsonSchema.Obj(listOf("from" to JsonSchema.Str(maxLength = 120), "to" to JsonSchema.Str(maxLength = 120), "why" to JsonSchema.Str(maxLength = 200))), maxItems = 5),
        "rewrite" to JsonSchema.Str(maxLength = 600),
        "vocabulary" to JsonSchema.Arr(JsonSchema.Obj(listOf("word" to JsonSchema.Str(maxLength = 60), "meaning" to JsonSchema.Str(maxLength = 120), "example" to JsonSchema.Str(maxLength = 200))), maxItems = 3),
        "turn_level" to JsonSchema.Str(enum = OpiRate.LEVELS),
        "pragmatics" to PragmaticFlag.SCHEMA,
    ))

    override fun messages(input: Input): List<ChatMessage> {
        val lang = languageName(input.language)
        return listOf(
            ChatMessage(Role.SYSTEM, listOf(
                "You are a $lang tutor giving feedback on one answer from a practice interview.",
                "Register: ${input.registerNotes}",
                "corrected = the answer with the fewest changes that make it correct and appropriate (identical if nothing is wrong); changes lists " +
                    "each change (from, to, why in English); rewrite = how a native speaker would naturally say it; vocabulary = 1–3 useful words " +
                    "(word in $lang, meaning and example in English/$lang); turn_level = the ILR level the answer demonstrates.",
                PragmaticFlag.instructions(lang, input.culturalNotes),
            ).joinToString("\n")),
            ChatMessage(Role.USER, "Interviewer: ${input.question}\nCandidate: ${input.answer}"),
        )
    }

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = listOfNotNull(
        ScriptCheck.requireLanguage("rewrite", output.rewrite, input.language),
        if (input.answer.isNotBlank()) Validation.minimalEdit(input.answer, output.corrected) else null,
        if (IlrLevel.parse(output.turnLevel) == null) "unknown turn_level" else null,
        if (output.vocabulary.size > 3) "at most three vocabulary notes" else null,
    ) + PragmaticFlag.problems(output.pragmatics, input.language)
}

/** `partner_reply` (C-11, Off mode): the conversation partner's next line only; no analysis of the learner. */
class PartnerReply : PromptTask<TopicTurn.Input, PartnerReply.Output> {
    @Serializable
    data class Output(val reply: String, @SerialName("reply_english") val replyEnglish: String = "")

    override val name = "partner_reply"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.5
    override val maxTokens = 400
    override val schema = JsonSchema.Obj(listOf("reply" to JsonSchema.Str(maxLength = 400), "reply_english" to JsonSchema.Str(maxLength = 400)))

    override fun messages(input: TopicTurn.Input): List<ChatMessage> {
        val full = TopicTurn().messages(input)
        val sys = full.first().content.lineSequence().takeWhile { !it.startsWith("Then give feedback") }.joinToString("\n")
        return listOf(ChatMessage(Role.SYSTEM, sys + "\nOnly reply; do not correct or comment on the learner's language."), full.last())
    }

    override fun validate(input: TopicTurn.Input, output: Output, context: ValidationContext): List<String> =
        listOfNotNull(ScriptCheck.requireLanguage("reply", output.reply, input.language))
}

/**
 * Runs interview feedback jobs one at a time behind the conversation (BRIEF_PHASE8 §B.5 "recording without
 * showing"): a job waits while the session is generating the interviewer's next line ([foregroundBusy]), so feedback
 * never delays the conversation; it catches up in pauses and at the end ([drain]). Results are delivered in turn order.
 */
class FeedbackQueue(private val gateway: AiGateway, scope: CoroutineScope, private val onRecord: (TurnFeedbackRecord) -> Unit) {
    private class FeedbackJob(val turnIndex: Int, val input: TurnFeedback.Input, val done: CompletableDeferred<Unit> = CompletableDeferred())

    private val jobs = Channel<FeedbackJob>(Channel.UNLIMITED)
    private val busy = MutableStateFlow(false)
    private val pending = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> get() = pending
    private val worker: kotlinx.coroutines.Job = scope.launch {
        for (job in jobs) {
            busy.first { !it } // the partner's next line first
            val record = when (val r = gateway.run(TurnFeedback(), job.input)) {
                is AiResult.Ok -> r.value.let { o ->
                    TurnFeedbackRecord(job.turnIndex, job.input.answer, o.corrected.takeIf { it.trim() != job.input.answer.trim() }, o.changes, o.rewrite,
                        o.vocabulary, o.turnLevel, o.pragmatics, r.engine)
                }
                else -> TurnFeedbackRecord(job.turnIndex, job.input.answer)
            }
            onRecord(record)
            pending.value--
            job.done.complete(Unit)
        }
    }
    private val all = mutableListOf<CompletableDeferred<Unit>>()

    fun foregroundBusy(value: Boolean) {
        busy.value = value
    }

    fun enqueue(turnIndex: Int, input: TurnFeedback.Input) {
        val job = FeedbackJob(turnIndex, input)
        all += job.done
        pending.value++
        jobs.trySend(job)
    }

    /** Waits for every queued job (the end of the session). */
    suspend fun drain() {
        busy.value = false
        all.toList().forEach { it.await() }
    }

    fun close() {
        jobs.close()
        worker.cancel()
    }
}
