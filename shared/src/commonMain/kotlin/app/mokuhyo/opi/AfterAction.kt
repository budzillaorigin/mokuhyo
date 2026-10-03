package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiResult
import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Role
import app.mokuhyo.ai.Validation
import app.mokuhyo.ai.ValidationContext
import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.ScriptCheck
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The After Action Brief (BRIEF_PHASE8 §B.5): generated when a speaking session ends, shown as a screen, saved with
 * the conversation (`conversation.aabJson`) and listed in the PDF report.
 *  1. summary — duration, turns, topic/persona, rolling level per turn, three things to work on next;
 *  2. turn by turn — every [TurnFeedbackRecord] with the partner's reply for context;
 *  3. patterns — recurring grammar errors, register slips, avoidance, fluency statistics;
 *  4. cultural appropriateness — pragmatic flags grouped by culture-card tag (not part of the ILR scale);
 *  5. for interview practice — the phase map and the rating with evidence (the OPI part of the session record).
 */
@Serializable
data class AfterActionBrief(
    val mode: String,
    val activity: String,
    val title: String,
    val persona: String? = null,
    val durationSec: Long,
    val turns: Int,
    val levelTrack: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val records: List<TurnFeedbackRecord> = emptyList(),
    val patterns: Patterns = Patterns(),
    val fluency: FluencyStats? = null,
    val cultural: List<CulturalGroup> = emptyList(),
    val engine: String? = null,
) {
    @Serializable
    data class Patterns(
        val grammar: List<String> = emptyList(),
        val register: List<String> = emptyList(),
        val avoidance: List<String> = emptyList(),
    )

    @Serializable
    data class FluencyStats(val composite: Int, val wordsPerMinute: Int, val pausePercent: Int, val falseStarts: Int)

    /** Flags under one culture-card tag; [cardId]/[cardTitle] = the card it relates to, when the pack has one. */
    @Serializable
    data class CulturalGroup(val tag: String, val cardId: String? = null, val cardTitle: String? = null, val flags: List<PragmaticFlag>)

    companion object {
        /** Which culture-card tag a flag kind belongs to (BRIEF_PHASE8 §B.4.1 tag vocabulary). */
        val TAG_OF_KIND = mapOf("register" to "rank", "face" to "face", "directness" to "refusal", "ritual" to "hospitality", "taboo" to "religion")
    }
}

/** `aab_summary`: the three next steps and the recurring patterns of a session, from its feedback records. */
class AabSummary : PromptTask<AabSummary.Input, AabSummary.Output> {
    data class Input(val language: String, val records: List<TurnFeedbackRecord>, val transcript: List<Turn>)

    @Serializable
    data class Output(
        @SerialName("next_steps") val nextSteps: List<String>,
        val grammar: List<String> = emptyList(),
        val register: List<String> = emptyList(),
        val avoidance: List<String> = emptyList(),
    )

    override val name = "aab_summary"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.2
    override val maxTokens = 700
    override val schema = JsonSchema.Obj(listOf(
        "next_steps" to JsonSchema.Arr(JsonSchema.Str(maxLength = 240), maxItems = 3),
        "grammar" to JsonSchema.Arr(JsonSchema.Str(maxLength = 200), maxItems = 4),
        "register" to JsonSchema.Arr(JsonSchema.Str(maxLength = 200), maxItems = 4),
        "avoidance" to JsonSchema.Arr(JsonSchema.Str(maxLength = 200), maxItems = 3),
    ))

    override fun messages(input: Input): List<ChatMessage> {
        val lang = Languages.of(input.language)?.nameEnglish ?: input.language
        val fb = input.records.joinToString("\n") { r ->
            "- said: ${r.learner}" + (r.changes.takeIf { it.isNotEmpty() }?.joinToString("; ", " | changes: ") { "${it.from} → ${it.to} (${it.why})" } ?: "") +
                (r.pragmatics.takeIf { it.isNotEmpty() }?.joinToString("; ", " | cultural: ") { "${it.kind}: ${it.why}" } ?: "")
        }
        return listOf(
            ChatMessage(Role.SYSTEM, "You write the summary of an after-action review of a $lang speaking practice session for a military " +
                "linguist. All output in English. next_steps: exactly three concrete, specific things to practise next. grammar: recurring " +
                "grammar errors (each once, with an example). register: register or politeness slips. avoidance: topics or structures the " +
                "learner steered around (e.g. never used past tense, avoided giving an opinion). Use empty lists when there is nothing."),
            ChatMessage(Role.USER, "Turn feedback:\n$fb\n\nTranscript:\n" + input.transcript.joinToString("\n") {
                (if (it.speaker == Speaker.LEARNER) "Learner: " else "Partner: ") + it.text
            }),
        )
    }

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = listOfNotNull(
        if (output.nextSteps.size !in 1..3) "next_steps must have three items" else null,
        output.nextSteps.firstOrNull { ScriptCheck.requireEnglish("next_steps", it) != null }?.let { "next_steps must be in English" },
        output.nextSteps.firstOrNull()?.let { Validation.length("next_steps[0]", it, min = 8, max = 240) },
    )
}

/** Assembles an [AfterActionBrief]; the summary call falls back to the records' own recurring errors when no model answers. */
object AfterActionBuilder {
    suspend fun build(
        gateway: AiGateway?, language: String, mode: CorrectionsMode, activity: SpeakingActivity, title: String, persona: String?,
        durationSec: Long, transcript: List<Turn>, records: List<TurnFeedbackRecord>, levelTrack: List<String>,
        fluency: AfterActionBrief.FluencyStats?, cardFor: (String) -> Pair<String, String>?,
    ): AfterActionBrief {
        val turns = transcript.count { it.speaker == Speaker.LEARNER }
        if (!mode.records) return AfterActionBrief(mode.id, activity.id, title, persona, durationSec, turns, levelTrack, fluency = fluency)
        val recurring = records.flatMap { it.changes }.groupBy { it.from.lowercase().trim() to it.to.lowercase().trim() }.values
            .filter { it.size >= 2 }.map { "“${it.first().from}” → “${it.first().to}” (${it.size}×): ${it.first().why}" }
        val registerSlips = records.flatMap { it.pragmatics }.filter { it.kind == "register" }.map { "“${it.what}”: ${it.why}" }.distinct()
        var summary = AabSummary.Output(nextSteps = emptyList(), grammar = recurring, register = registerSlips)
        var engine: String? = null
        if (gateway != null && records.isNotEmpty()) {
            when (val r = gateway.run(AabSummary(), AabSummary.Input(language, records, transcript))) {
                is AiResult.Ok -> { summary = r.value; engine = r.engine }
                else -> {}
            }
        }
        val nextSteps = summary.nextSteps.ifEmpty {
            (recurring.take(2).map { "Drill this correction: $it" } + registerSlips.take(1).map { "Practise the polite form: $it" })
                .ifEmpty { listOf("Repeat this session and aim for longer answers.") }
        }
        val cultural = records.flatMap { it.pragmatics }.groupBy { AfterActionBrief.TAG_OF_KIND[it.kind] ?: it.kind }.map { (tag, flags) ->
            val card = cardFor(tag)
            AfterActionBrief.CulturalGroup(tag, card?.first, card?.second, flags)
        }
        return AfterActionBrief(
            mode.id, activity.id, title, persona, durationSec, turns, levelTrack, nextSteps.take(3), records,
            AfterActionBrief.Patterns(summary.grammar.ifEmpty { recurring }, summary.register.ifEmpty { registerSlips }, summary.avoidance),
            fluency, cultural, engine,
        )
    }
}
