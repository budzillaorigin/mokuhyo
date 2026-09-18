package app.tsumugi.ai.prompts

import app.tsumugi.ai.ChatMessage
import app.tsumugi.ai.JsonSchema
import app.tsumugi.ai.PromptTask
import app.tsumugi.ai.Validation
import app.tsumugi.ai.ValidationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `roleplay_turn`: the partner's next line in a scenario role-play (ordering food, asking directions, …). */
class RoleplayTurn(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<RoleplayTurn.Input, RoleplayTurn.Output> {
    data class Input(
        /** English description of the situation. */
        val scenario: String,
        val partnerRole: String,
        val learnerRole: String,
        /** What the learner is trying to accomplish; the partner reports when it has happened. */
        val goal: String,
        val level: String = "N4",
        val history: List<Turn> = emptyList(),
    )

    @Serializable
    data class Output(
        val reply: String,
        val translation: String,
        val hint: String = "",
        @SerialName("goal_reached") val goalReached: Boolean = false,
    )

    override val name = "roleplay_turn"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.7
    override val maxTokens = 320
    override val schema = JsonSchema.Obj(
        listOf(
            "reply" to JsonSchema.Str(maxLength = 160),
            "translation" to JsonSchema.Str(maxLength = 320),
            "hint" to JsonSchema.Str(maxLength = 200),
            "goal_reached" to JsonSchema.Bool,
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: role-play in Japanese. You are the ${input.partnerRole}; the learner is the ${input.learnerRole}.",
            "Scenario: ${input.scenario}",
            "The learner's goal: ${input.goal}",
            "Speak only as the ${input.partnerRole}, in one or two short sentences a ${input.level} learner can follow.",
            "Stay in the scene even if the learner makes mistakes; do not correct them.",
            "translation is your reply in English. hint is a short English tip about what the learner could say next.",
            "Set goal_reached to true once the learner has accomplished the goal.",
        ),
        user(
            if (input.history.isEmpty()) {
                "Start the conversation with your opening line."
            } else {
                "Conversation so far:\n" + transcript(input.history, input.learnerRole, input.partnerRole) + "\nYour next line."
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        Validation.requireJapanese("reply", output.reply),
        Validation.length("reply", output.reply, max = 160),
        Validation.requireEnglish("translation", output.translation),
        output.hint.takeIf { it.isNotBlank() }?.let { Validation.requireEnglish("hint", it) },
        if (!context.isKnownJapanese(output.reply)) "reply contains words the dictionary doesn't know" else null,
    )

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)
}

/** OPI-style interview phases, in order (BRIEF §3.5). */
@Serializable
enum class OpiPhase {
    @SerialName("warmup") WARMUP,
    @SerialName("level_check") LEVEL_CHECK,
    @SerialName("probe") PROBE,
    @SerialName("roleplay") ROLEPLAY,
    @SerialName("winddown") WINDDOWN,
    ;

    val wireName: String get() = name.lowercase()
}

/** `opi_interviewer_turn`: the interviewer's next question in a simulated OPI-style speaking test. */
class OpiInterviewerTurn(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<OpiInterviewerTurn.Input, OpiInterviewerTurn.Output> {
    data class Input(
        val phase: OpiPhase,
        /** Working level hypothesis, e.g. "Intermediate Mid". */
        val targetLevel: String,
        val history: List<Turn> = emptyList(),
        val turnsInPhase: Int = 0,
    )

    @Serializable
    data class Output(
        val utterance: String,
        @SerialName("next_phase") val nextPhase: OpiPhase,
        val topic: String = "",
    )

    override val name = "opi_interviewer_turn"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.6
    override val maxTokens = 320
    override val schema = JsonSchema.Obj(
        listOf(
            "utterance" to JsonSchema.Str(maxLength = 200),
            "next_phase" to JsonSchema.Str(enum = OpiPhase.entries.map { it.wireName }),
            "topic" to JsonSchema.Str(maxLength = 80),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: you are a friendly interviewer running a practice oral proficiency interview in Japanese. This is practice, not an official test.",
            "Current phase: ${input.phase.wireName} (turn ${input.turnsInPhase + 1} of this phase). Working level: ${input.targetLevel}.",
            "Phases: warmup (easy personal questions), level_check (questions at the working level), probe (one step harder, " +
                "to find where speech breaks down), roleplay (set up a short situation and play a role), winddown (easy closing).",
            "Ask exactly one question or prompt, in Japanese, with no English. Do not correct the candidate.",
            "next_phase is the phase for the following turn: stay, or move forward when this phase has done its job. Never go back.",
            "topic is a two-to-five-word English label for the subject of your question.",
        ),
        user(
            if (input.history.isEmpty()) {
                "Begin the interview."
            } else {
                "Interview so far:\n" + transcript(input.history, "Candidate", "Interviewer") + "\nYour next turn."
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        Validation.requireJapanese("utterance", output.utterance),
        Validation.length("utterance", output.utterance, max = 200),
        if (output.nextPhase < input.phase) "next_phase goes back to an earlier phase" else null,
        if (!context.isKnownJapanese(output.utterance)) "utterance contains words the dictionary doesn't know" else null,
    )

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)
}

/** `opi_rate`: an unofficial ACTFL-style estimate from the candidate's side of an interview. */
class OpiRate(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<OpiRate.Input, OpiRate.Output> {
    data class Input(val history: List<Turn>)

    @Serializable
    data class Output(
        val level: String,
        val functions: Int,
        val accuracy: Int,
        val vocabulary: Int,
        val fluency: Int,
        val rationale: String,
        val strengths: List<String> = emptyList(),
        @SerialName("next_steps") val nextSteps: List<String> = emptyList(),
    )

    override val name = "opi_rate"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.1
    override val maxTokens = 700
    override val schema = JsonSchema.Obj(
        listOf(
            "level" to JsonSchema.Str(enum = LEVELS),
            "functions" to JsonSchema.Integer,
            "accuracy" to JsonSchema.Integer,
            "vocabulary" to JsonSchema.Integer,
            "fluency" to JsonSchema.Integer,
            "rationale" to JsonSchema.Str(maxLength = 1000),
            "strengths" to JsonSchema.Arr(JsonSchema.Str(maxLength = 200), maxItems = 4),
            "next_steps" to JsonSchema.Arr(JsonSchema.Str(maxLength = 200), maxItems = 4),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: estimate the candidate's speaking level from a practice interview, using the ACTFL scale " +
                "(${LEVELS.joinToString(", ")}). This is an unofficial practice estimate.",
            "Judge only the candidate's lines. Rate the sustained level, not the best single answer.",
            "Score functions (tasks they can handle), accuracy, vocabulary and fluency from 1 to 5.",
            "rationale explains the rating in English, citing what the candidate said. strengths and next_steps are short English phrases.",
        ),
        user("Interview:\n" + transcript(input.history, "Candidate", "Interviewer")),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        if (output.level !in LEVELS) "unknown level ${output.level}" else null,
        listOf(output.functions, output.accuracy, output.vocabulary, output.fluency)
            .takeIf { s -> s.any { it !in 1..5 } }?.let { "scores must be 1 to 5" },
        Validation.requireEnglish("rationale", output.rationale),
        Validation.length("rationale", output.rationale, min = 20, max = 1000),
        if (input.history.none { it.speaker == Speaker.LEARNER }) "there is nothing from the candidate to rate" else null,
    )

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)

    companion object {
        val LEVELS = listOf(
            "Novice Low", "Novice Mid", "Novice High",
            "Intermediate Low", "Intermediate Mid", "Intermediate High",
            "Advanced Low", "Advanced Mid", "Advanced High",
            "Superior",
        )
    }
}
