package app.tsumugi.speaking

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.ContextWindow
import app.tsumugi.ai.prompts.CorrectSentence
import app.tsumugi.ai.prompts.NaturalRewrite
import app.tsumugi.ai.prompts.RoleplayTurn
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.ai.prompts.Turn
import app.tsumugi.practice.Register
import app.tsumugi.practice.Scenario
import app.tsumugi.practice.ScriptedTurn

/** One line of a role-play. [engine] is set when a model wrote it (shown with the "AI-generated" badge). */
data class ConversationLine(
    val speaker: Speaker,
    val japanese: String,
    val english: String = "",
    val hint: String = "",
    val engine: String? = null,
) {
    val aiGenerated: Boolean get() = engine != null
}

/** Feedback on one learner line (Kigaru loop: say it → see the natural version → say it again). */
data class TurnFeedback(
    val correction: CorrectSentence.Output?,
    val natural: NaturalRewrite.Output?,
    val engine: String?,
    /** Why there's no feedback (no model set up, output failed checks). */
    val unavailable: String? = null,
)

/**
 * A scenario role-play (BRIEF §5.10). With a model, the partner answers in character through `roleplay_turn`;
 * without one, the scenario's scripted turns play in order and each shows a sample answer as the hint
 * (BRIEF §7.3 fallback), so the feature works fully offline with no model.
 *
 * The mode is fixed at the first partner turn (F-23): a session that started with a model never splices a scripted
 * line into the AI conversation. If the model fails mid-conversation, [start]/[reply]/[retry] return null and
 * [modelFailure] says why; the UI shows it as a banner with a retry, and the learner's line stays in the transcript.
 */
class RoleplaySession(
    val scenario: Scenario,
    private val scripted: List<ScriptedTurn>,
    private val gateway: AiGateway,
    private val level: String = "N${scenario.jlpt}",
) {
    private val scriptedTask = RoleplayTurn { input -> scriptedReply(input.history.count { it.speaker == Speaker.LEARNER }) }
    private val modelTask = RoleplayTurn()
    private val lines = mutableListOf<ConversationLine>()
    private val corrections = HashMap<String, CorrectSentence.Output>()
    private val startedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
    private var saved: ConversationRecord? = null

    val transcript: List<ConversationLine> get() = lines.toList()
    var goalReached: Boolean = false
        private set

    /** True when no model was configured at the start: the scripted turns are playing. Null before [start]. */
    var isScripted: Boolean? = null
        private set

    /** Why the last partner turn failed (model error, timeout, bad output); null when it didn't. Shown as a banner. */
    var modelFailure: String? = null
        private set

    /** The partner's opening line, or null when the model failed ([modelFailure]). */
    @Throws(Exception::class)
    suspend fun start(): ConversationLine? {
        if (lines.isNotEmpty()) return lines.first()
        return partnerTurn()
    }

    /** Adds the learner's line and returns the partner's reply, or null when the model failed ([modelFailure]). */
    @Throws(Exception::class)
    suspend fun reply(text: String): ConversationLine? {
        lines += ConversationLine(Speaker.LEARNER, text.trim())
        return partnerTurn()
    }

    /** Asks the model again for the partner turn that failed. */
    @Throws(Exception::class)
    suspend fun retry(): ConversationLine? {
        if (modelFailure == null) return lines.lastOrNull()?.takeIf { it.speaker == Speaker.PARTNER }
        return partnerTurn()
    }
    /** Corrections and a natural rewrite for a learner line; runs only when asked, to keep turns fast on-device. */
    @Throws(Exception::class)
    suspend fun feedback(text: String): TurnFeedback {
        val register = when (scenario.register) {
            Register.CASUAL -> NaturalRewrite.Register.CASUAL
            Register.POLITE -> NaturalRewrite.Register.POLITE
            Register.KEIGO -> NaturalRewrite.Register.FORMAL
        }
        val correction = gateway.run(CorrectSentence(), CorrectSentence.Input(text, level, scenario.setting))
        val natural = gateway.run(NaturalRewrite(), NaturalRewrite.Input(text, register))
        (correction as? AiResult.Ok)?.value?.let { corrections[text.trim()] = it }
        val engine = (correction as? AiResult.Ok)?.engine ?: (natural as? AiResult.Ok)?.engine
        val reason = listOf(correction, natural).filterIsInstance<AiResult.Unavailable>().firstOrNull()?.reason
        return TurnFeedback((correction as? AiResult.Ok)?.value, (natural as? AiResult.Ok)?.value, engine, reason.takeIf { engine == null })
    }

    /**
     * Stores the role-play as a SCENARIO conversation (BRIEF_V2 G-02), once, with the corrections the learner asked
     * for; they feed the recurring-error log. Null when the learner said nothing.
     */
    @Throws(Exception::class)
    suspend fun save(conversations: ConversationService): ConversationRecord? {
        saved?.let { return it }
        val turns = lines.map { l ->
            if (l.speaker == Speaker.LEARNER) {
                ConversationTurn(ConversationTurn.LEARNER, l.japanese, corrections = corrections[l.japanese])
            } else {
                ConversationTurn(ConversationTurn.PARTNER, l.japanese, l.english)
            }
        }
        val engine = lines.firstNotNullOfOrNull { it.engine }
        return conversations.save(ConversationMode.SCENARIO, scenario.id, level, turns, startedAt, engine).also { saved = it }
    }

    private suspend fun partnerTurn(): ConversationLine? {
        val scriptedMode = isScripted ?: (!gateway.hasModel()).also { isScripted = it }
        val history = lines.map { Turn(it.speaker, it.japanese) }
        val base = RoleplayTurn.Input(
            scenario = "${scenario.setting}. ${scenario.titleEn}.",
            partnerRole = scenario.partnerRole,
            learnerRole = scenario.learnerRole,
            goal = scenario.goals.joinToString("; "),
            level = level,
            history = history,
        )
        val line = if (scriptedMode) {
            // The scripted fallback counts learner turns, so it always sees the whole history.
            when (val result = gateway.run(scriptedTask, base)) {
                is AiResult.Ok -> partnerLine(result.value, result.engine)
                is AiResult.Fallback -> partnerLine(result.value, null)
                is AiResult.Unavailable -> {
                    goalReached = true
                    ConversationLine(Speaker.PARTNER, "ありがとうございました。", "Thank you very much.")
                }
            }
        } else {
            when (val result = gateway.run(modelTask, base.copy(history = window(base)))) {
                is AiResult.Ok -> partnerLine(result.value, result.engine)
                is AiResult.Fallback -> return failed(result.reason)
                is AiResult.Unavailable -> return failed(result.reason)
            }
        }
        modelFailure = null
        lines += line
        return line
    }

    private fun failed(reason: String): ConversationLine? {
        modelFailure = reason
        return null
    }

    private fun partnerLine(out: RoleplayTurn.Output, engine: String?): ConversationLine {
        if (out.goalReached) goalReached = true
        return ConversationLine(Speaker.PARTNER, out.reply, out.translation, out.hint, engine)
    }

    /**
     * The most recent turns that fit the model's window: `n_ctx − maxTokens − margin`, minus the system prompt and
     * instructions (estimated with an empty history). Older turns are dropped first (F-23).
     */
    private fun window(input: RoleplayTurn.Input): List<Turn> {
        val fixed = ContextWindow.estimateTokens(AiGateway.withJsonContract(modelTask.messages(input.copy(history = emptyList())), modelTask.schema))
        val budget = ContextWindow.budget(gateway.contextSize(), modelTask.maxTokens)
        return ContextWindow.fitLatest(input.history, fixed, budget) { ContextWindow.estimateTokens(it.text) + TURN_OVERHEAD }
    }

    private fun scriptedReply(learnerTurns: Int): RoleplayTurn.Output? {
        val turn = scripted.getOrNull(learnerTurns) ?: return null
        return RoleplayTurn.Output(turn.partnerJa, turn.partnerEn, turn.sampleAnswer, goalReached = learnerTurns >= scripted.lastIndex)
    }

    private companion object {
        /** Role label and line break per transcript line. */
        const val TURN_OVERHEAD = 8
    }
}
