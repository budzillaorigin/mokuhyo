package app.tsumugi.speaking

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.ContextWindow
import app.tsumugi.ai.prompts.CorrectSentence
import app.tsumugi.ai.prompts.FreeTalkTurn
import app.tsumugi.ai.prompts.NaturalRewrite
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.ai.prompts.Turn
import kotlin.time.Clock

/**
 * Free talk (BRIEF §5.10, BRIEF_V2 G-02): open conversation with no scenario through the `free_talk_turn` prompt, at
 * the learner's rolling level ([ConversationService.partnerLevel]). It needs a model: without one, [start] returns
 * null with [unavailable] set, and the UI offers model setup (there is no scripted free talk; scenario role-plays
 * cover the no-model case).
 *
 * [feedback] runs `correct_sentence` and `natural_rewrite` on a learner line and keeps the correction with that line;
 * [finish] stores the conversation, and those corrections become the recurring-error log.
 */
class FreeTalkSession(
    private val gateway: AiGateway,
    private val conversations: ConversationService,
    /** JLPT label the partner speaks at ("N4"). */
    val level: String,
    val topic: String? = null,
    private val clock: Clock = Clock.System,
) {
    private val task = FreeTalkTurn()
    private val lines = mutableListOf<ConversationTurn>()
    private val startedAt = clock.now().toEpochMilliseconds()
    private var engine: String? = null
    private var saved: ConversationRecord? = null

    val transcript: List<ConversationTurn> get() = lines.toList()

    /** Why the last partner turn failed or free talk can't run; null when it didn't. Shown as a banner with Retry. */
    var unavailable: String? = null
        private set

    /** The topic label the model reported for its last line (for the header). */
    var currentTopic: String = topic.orEmpty()
        private set

    /** The partner's opening line, or null (no model, or the model failed: see [unavailable]). */
    @Throws(Exception::class)
    suspend fun start(): ConversationTurn? {
        lines.firstOrNull()?.let { return it }
        return partnerTurn()
    }

    /** Adds the learner's line and returns the partner's reply, or null when the model failed. */
    @Throws(Exception::class)
    suspend fun reply(text: String): ConversationTurn? {
        val line = text.trim()
        if (line.isEmpty()) return null
        lines += ConversationTurn(ConversationTurn.LEARNER, line)
        return partnerTurn()
    }

    /** Asks the model again after a failure. */
    @Throws(Exception::class)
    suspend fun retry(): ConversationTurn? {
        if (unavailable == null) return lines.lastOrNull()?.takeIf { !it.isLearner }
        return partnerTurn()
    }

    /**
     * Corrections and a natural version of the learner line at [index] in [transcript]. The correction is stored on
     * the line, so the error log sees it. Runs only when asked, to keep turns fast on-device.
     */
    @Throws(Exception::class)
    suspend fun feedback(index: Int): TurnFeedback {
        val line = lines.getOrNull(index)?.takeIf { it.isLearner } ?: return TurnFeedback(null, null, null, "not a learner line")
        val correction = gateway.run(CorrectSentence(), CorrectSentence.Input(line.ja, level))
        val natural = gateway.run(NaturalRewrite(), NaturalRewrite.Input(line.ja, NaturalRewrite.Register.POLITE))
        (correction as? AiResult.Ok)?.value?.let { c -> lines[index] = line.copy(corrections = c) }
        val engine = (correction as? AiResult.Ok)?.engine ?: (natural as? AiResult.Ok)?.engine
        val reason = listOf(correction, natural).filterIsInstance<AiResult.Unavailable>().firstOrNull()?.reason
        return TurnFeedback((correction as? AiResult.Ok)?.value, (natural as? AiResult.Ok)?.value, engine, reason.takeIf { engine == null })
    }

    /** Stores the conversation (once); null when the learner said nothing. */
    @Throws(Exception::class)
    suspend fun finish(): ConversationRecord? {
        saved?.let { return it }
        return conversations.save(ConversationMode.FREE_TALK, null, level, lines.toList(), startedAt, engine).also { saved = it }
    }

    private suspend fun partnerTurn(): ConversationTurn? {
        if (!gateway.hasModel()) {
            unavailable = NO_MODEL
            return null
        }
        val input = FreeTalkTurn.Input(level, lines.map { Turn(if (it.isLearner) Speaker.LEARNER else Speaker.PARTNER, it.ja) }, topic)
        return when (val result = gateway.run(task, input.copy(history = window(input)))) {
            is AiResult.Ok -> {
                unavailable = null
                engine = result.engine
                if (result.value.topic.isNotBlank()) currentTopic = result.value.topic
                ConversationTurn(ConversationTurn.PARTNER, result.value.reply, result.value.translation).also { lines += it }
            }
            is AiResult.Fallback -> null.also { unavailable = result.reason }
            is AiResult.Unavailable -> null.also { unavailable = result.reason }
        }
    }

    /** The latest turns that fit the model's context window (F-23), like role-play. */
    private fun window(input: FreeTalkTurn.Input): List<Turn> {
        val fixed = ContextWindow.estimateTokens(AiGateway.withJsonContract(task.messages(input.copy(history = emptyList())), task.schema))
        val budget = ContextWindow.budget(gateway.contextSize(), task.maxTokens)
        return ContextWindow.fitLatest(input.history, fixed, budget) { ContextWindow.estimateTokens(it.text) + 8 }
    }

    companion object {
        const val NO_MODEL = "free talk needs an AI model (on-device or your own endpoint)"
    }
}
