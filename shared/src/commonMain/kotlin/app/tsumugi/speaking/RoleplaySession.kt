package app.tsumugi.speaking

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
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
 */
class RoleplaySession(
    val scenario: Scenario,
    private val scripted: List<ScriptedTurn>,
    private val gateway: AiGateway,
    private val level: String = "N${scenario.jlpt}",
) {
    private val task = RoleplayTurn { input -> scriptedReply(input.history.count { it.speaker == Speaker.LEARNER }) }
    private val lines = mutableListOf<ConversationLine>()

    val transcript: List<ConversationLine> get() = lines.toList()
    var goalReached: Boolean = false
        private set

    /** The partner's opening line. */
    @Throws(Exception::class)
    suspend fun start(): ConversationLine {
        if (lines.isNotEmpty()) return lines.first()
        return partnerTurn()
    }

    /** Adds the learner's line and returns the partner's reply. */
    @Throws(Exception::class)
    suspend fun reply(text: String): ConversationLine {
        lines += ConversationLine(Speaker.LEARNER, text.trim())
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
        val engine = (correction as? AiResult.Ok)?.engine ?: (natural as? AiResult.Ok)?.engine
        val reason = listOf(correction, natural).filterIsInstance<AiResult.Unavailable>().firstOrNull()?.reason
        return TurnFeedback((correction as? AiResult.Ok)?.value, (natural as? AiResult.Ok)?.value, engine, reason.takeIf { engine == null })
    }

    private suspend fun partnerTurn(): ConversationLine {
        val input = RoleplayTurn.Input(
            scenario = "${scenario.setting}. ${scenario.titleEn}.",
            partnerRole = scenario.partnerRole,
            learnerRole = scenario.learnerRole,
            goal = scenario.goals.joinToString("; "),
            level = level,
            history = lines.map { Turn(it.speaker, it.japanese) },
        )
        val line = when (val result = gateway.run(task, input)) {
            is AiResult.Ok -> ConversationLine(Speaker.PARTNER, result.value.reply, result.value.translation, result.value.hint, result.engine)
                .also { if (result.value.goalReached) goalReached = true }
            is AiResult.Fallback -> ConversationLine(Speaker.PARTNER, result.value.reply, result.value.translation, result.value.hint)
                .also { if (result.value.goalReached) goalReached = true }
            is AiResult.Unavailable -> {
                goalReached = true
                ConversationLine(Speaker.PARTNER, "ありがとうございました。", "Thank you very much.")
            }
        }
        lines += line
        return line
    }

    private fun scriptedReply(learnerTurns: Int): RoleplayTurn.Output? {
        val turn = scripted.getOrNull(learnerTurns) ?: return null
        return RoleplayTurn.Output(turn.partnerJa, turn.partnerEn, turn.sampleAnswer, goalReached = learnerTurns >= scripted.lastIndex)
    }
}
