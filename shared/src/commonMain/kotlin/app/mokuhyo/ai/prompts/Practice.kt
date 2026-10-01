package app.mokuhyo.ai.prompts

import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Validation
import app.mokuhyo.ai.ValidationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * `free_talk_turn`: the partner's next line in free conversation (BRIEF §5.10 free talk, BRIEF_V2 G-02). No scenario
 * and no goal: a friendly partner who keeps the conversation going at the learner's level. It never corrects the
 * learner in-line; corrections come from `correct_sentence` on request, and feed the recurring-error log.
 */
class FreeTalkTurn : PromptTask<FreeTalkTurn.Input, FreeTalkTurn.Output> {
    data class Input(
        /** JLPT label the partner speaks at, from the rolling level estimate ("N4"). */
        val level: String = "N4",
        val history: List<Turn> = emptyList(),
        /** Optional English topic the learner picked ("my weekend"); null lets the partner choose. */
        val topic: String? = null,
    )

    @Serializable
    data class Output(
        val reply: String,
        val translation: String,
        /** Two-to-five-word English label for what the conversation is about now. */
        val topic: String = "",
    )

    override val name = "free_talk_turn"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.7
    override val maxTokens = 320
    override val schema = JsonSchema.Obj(
        listOf(
            "reply" to JsonSchema.Str(maxLength = 200),
            "translation" to JsonSchema.Str(maxLength = 400),
            "topic" to JsonSchema.Str(maxLength = 60),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: free conversation in Japanese. You are a friendly conversation partner, not a teacher, talking with a ${input.level} learner.",
            "Reply in one to three short sentences the learner can follow at ${input.level}, then ask one question that keeps the conversation going.",
            "React to what the learner said. Do not correct their mistakes and do not switch to English.",
            "translation is your reply in English. topic is a two-to-five-word English label for what you are talking about.",
        ),
        user(
            buildString {
                input.topic?.let { append("Suggested topic: ").append(it).append('\n') }
                if (input.history.isEmpty()) {
                    append("Start the conversation with a greeting and an easy question.")
                } else {
                    append("Conversation so far:\n").append(transcript(input.history, "Learner", "You")).append("\nYour next line.")
                }
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        Validation.requireJapanese("reply", output.reply),
        Validation.length("reply", output.reply, max = 200),
        Validation.requireEnglish("translation", output.translation),
        if (!context.isKnownJapanese(output.reply)) "reply contains words the dictionary doesn't know" else null,
    )
}

/**
 * `grade_production`: grades a learner's Japanese translation of an English prompt that should use one grammar point
 * (BRIEF §5.5 production reviews, BRIEF_V2 G-05). The rubric has three 0–2 scores; the app combines them with its own
 * rule checks (is the construction there, is its form consistent — [Input.constructionFound]) and always shows the
 * model answer next to the grade.
 */
class GradeProduction : PromptTask<GradeProduction.Input, GradeProduction.Output> {
    data class Input(
        val english: String,
        /** The reference sentence (the example the prompt was made from). */
        val modelAnswer: String,
        val grammarTitle: String,
        val grammarStructure: String,
        val grammarMeaning: String,
        val answer: String,
        val level: String = "N4",
        /** Rule check: the point's detection pattern matched the answer (null when the point has no pattern). */
        val constructionFound: Boolean? = null,
    )

    @Serializable
    data class Output(
        /** 0 = meaning lost, 1 = partly, 2 = says what the English says. */
        val meaning: Int,
        /** 0 = target grammar missing, 1 = attempted but wrong, 2 = used correctly. */
        val grammar: Int,
        /** 0 = several errors, 1 = one small error (particle, conjugation, kana), 2 = no errors. */
        val form: Int,
        /** A corrected version of the learner's answer ("" when it needs no change). */
        val corrected: String = "",
        /** One to three sentences in English for the learner. */
        val feedback: String,
    ) {
        val total: Int get() = meaning + grammar + form
    }

    override val name = "grade_production"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.1
    override val maxTokens = 400
    override val schema = JsonSchema.Obj(
        listOf(
            "meaning" to JsonSchema.Integer,
            "grammar" to JsonSchema.Integer,
            "form" to JsonSchema.Integer,
            "corrected" to JsonSchema.Str(maxLength = 300),
            "feedback" to JsonSchema.Str(maxLength = 500),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: grade a ${input.level} learner's Japanese translation that must use the grammar point ${input.grammarTitle} " +
                "(${input.grammarStructure}: ${input.grammarMeaning}).",
            "Score three things from 0 to 2: meaning (does it say what the English says), grammar (is ${input.grammarTitle} used correctly), " +
                "form (particles, conjugation, spelling). Other correct wordings than the model answer deserve full marks.",
            "corrected is the learner's sentence with the fewest changes that make it right, or \"\" if it is already right.",
            "feedback is one to three sentences in English: what is good and what to fix.",
        ),
        user(
            buildString {
                append("English: ").append(input.english).append('\n')
                append("Model answer: ").append(input.modelAnswer).append('\n')
                append("Learner's answer: ").append(input.answer)
                if (input.constructionFound == false) append("\nNote: the app did not find ${input.grammarTitle} in the learner's answer.")
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        listOf(output.meaning, output.grammar, output.form).takeIf { s -> s.any { it !in 0..2 } }?.let { "scores must be 0 to 2" },
        Validation.requireEnglish("feedback", output.feedback),
        Validation.length("feedback", output.feedback, min = 10, max = 500),
        output.corrected.takeIf { it.isNotBlank() }?.let { Validation.requireJapanese("corrected", it) },
        if (input.constructionFound == false && output.grammar == 2) "grammar scored 2 but the construction is not in the answer" else null,
        if (output.total == 6 && output.corrected.isNotBlank() && output.corrected.trim() != input.answer.trim()) {
            "full marks but a correction was given"
        } else {
            null
        },
    )

    companion object {
        /** Answers scoring at least this many of 6 are correct (with the construction present), from [CLOSE_TOTAL] close. */
        const val CORRECT_TOTAL = 5
        const val CLOSE_TOTAL = 3
    }
}
