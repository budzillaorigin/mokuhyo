package app.mokuhyo.ai.prompts

import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Validation
import app.mokuhyo.ai.ValidationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `grade_translation`: grades a learner's translation (J→E or E→J, written or a sight-translation transcript) against
 * a reference on four criteria, 0–4 each: accuracy, completeness, register, naturalness (BRIEF_V2 §6.12, DECISIONS
 * D-271). The grade is practice feedback, always labeled AI-generated (rule 10) and never an official score. Without a
 * reference (imported text) the model judges against the source alone.
 */
class GradeTranslation : PromptTask<GradeTranslation.Input, GradeTranslation.Output> {
    data class Input(
        val source: String,
        val attempt: String,
        /** The model answer; "" for imported text without one. */
        val reference: String = "",
        /** True for J→E, false for E→J. */
        val japaneseToEnglish: Boolean = true,
        val genre: String = "news",
        val level: String = "N2",
        /** A sight-translation transcript (speech to text): punctuation, fillers and small slips don't count. */
        val spoken: Boolean = false,
        /** What a good translation must get right (the completeness list). */
        val keyPoints: List<String> = emptyList(),
        /** The register the target text should use, in English. */
        val register: String = "",
    ) {
        val sourceLanguage: String get() = if (japaneseToEnglish) "Japanese" else "English"
        val targetLanguage: String get() = if (japaneseToEnglish) "English" else "Japanese"
    }

    @Serializable
    data class Issue(
        /** mistranslation | omission | addition | register | unnatural | grammar | term. */
        val kind: String,
        /** The learner's words the issue is about ("" for an omission). */
        @SerialName("attempt_span") val attemptSpan: String = "",
        /** The matching words of the reference ("" when there is none). */
        @SerialName("reference_span") val referenceSpan: String = "",
        /** One English sentence. */
        val note: String,
    )

    @Serializable
    data class Output(
        val accuracy: Int,
        val completeness: Int,
        val register: Int,
        val naturalness: Int,
        /** Two to four English sentences: what worked, what to fix first. */
        val feedback: String,
        val issues: List<Issue> = emptyList(),
        /** The learner's translation with the fewest changes that fix it, in the target language ("" if fine). */
        val better: String = "",
    ) {
        val total: Int get() = accuracy + completeness + register + naturalness

        /** 0–100, the skill line's unit: total × 100 / 16, rounded. */
        val percent: Int get() = (total * 100 + 8) / 16
    }

    override val name = "grade_translation"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.1
    override val maxTokens = 900
    override val schema = JsonSchema.Obj(
        listOf(
            "accuracy" to JsonSchema.Integer,
            "completeness" to JsonSchema.Integer,
            "register" to JsonSchema.Integer,
            "naturalness" to JsonSchema.Integer,
            "feedback" to JsonSchema.Str(maxLength = 700),
            "issues" to JsonSchema.Arr(
                JsonSchema.Obj(
                    listOf(
                        "kind" to JsonSchema.Str(enum = KINDS),
                        "attempt_span" to JsonSchema.Str(maxLength = 200),
                        "reference_span" to JsonSchema.Str(maxLength = 200),
                        "note" to JsonSchema.Str(maxLength = 240),
                    ),
                ),
                maxItems = MAX_ISSUES,
            ),
            "better" to JsonSchema.Str(maxLength = 1500),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: grade a ${input.level} learner's ${input.sourceLanguage}-to-${input.targetLanguage} translation of a " +
                "${input.genre} text. This is practice feedback, not an official score.",
            "Score four things from 0 to 4 (0 = missing or wrong, 2 = understandable with clear problems, 4 = as good as the reference):",
            "accuracy: the meaning is right, nothing mistranslated; completeness: every point of the source is there " +
                "(check the key points); register: the style fits the genre and the target register; naturalness: it " +
                "reads like something a native ${input.targetLanguage} writer would write.",
            if (input.reference.isNotBlank()) {
                "Compare with the reference, but accept any correct wording: a different good translation scores as high as the reference."
            } else {
                "There is no reference: judge against the source text alone."
            },
            if (input.spoken) {
                "The translation is a speech-to-text transcript of a timed sight translation: ignore punctuation, " +
                    "capitalization, filler words and small transcription slips; judge meaning and register."
            } else {
                "The translation was written."
            },
            "issues: up to $MAX_ISSUES concrete problems, most important first. kind is one of ${KINDS.joinToString(", ")}. " +
                "attempt_span copies the learner's exact words (\"\" for an omission); reference_span copies the reference's " +
                "exact words (\"\" if none); note is one English sentence.",
            "feedback is two to four sentences in English. better is the learner's translation with the fewest changes " +
                "that fix it, in ${input.targetLanguage}, or \"\" if it needs none.",
        ),
        user(
            buildString {
                append("Source (").append(input.sourceLanguage).append("):\n").append(input.source).append("\n\n")
                if (input.reference.isNotBlank()) append("Reference (").append(input.targetLanguage).append("):\n").append(input.reference).append("\n\n")
                if (input.keyPoints.isNotEmpty()) append("Key points:\n").append(input.keyPoints.joinToString("\n") { "- $it" }).append("\n\n")
                if (input.register.isNotBlank()) append("Target register: ").append(input.register).append("\n\n")
                append("Learner's translation:\n").append(input.attempt)
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> {
        val scores = listOf(output.accuracy, output.completeness, output.register, output.naturalness)
        val problems = issues(
            scores.takeIf { s -> s.any { it !in 0..4 } }?.let { "scores must be 0 to 4" },
            Validation.requireEnglish("feedback", output.feedback),
            Validation.length("feedback", output.feedback, min = 20, max = 700),
            output.issues.takeIf { it.size > MAX_ISSUES }?.let { "too many issues" },
            output.better.takeIf { it.isNotBlank() }?.let {
                if (input.japaneseToEnglish) Validation.requireEnglish("better", it) else Validation.requireJapanese("better", it)
            },
        ).toMutableList()
        val attempt = squash(input.attempt)
        val reference = squash(input.reference)
        output.issues.forEachIndexed { i, issue ->
            if (issue.kind !in KINDS) problems += "issue $i has an unknown kind"
            Validation.requireEnglish("issue $i note", issue.note)?.let { problems += it }
            if (issue.attemptSpan.isNotBlank() && squash(issue.attemptSpan) !in attempt) problems += "issue $i quotes words the learner didn't write"
            if (issue.referenceSpan.isNotBlank() && (reference.isEmpty() || squash(issue.referenceSpan) !in reference)) {
                problems += "issue $i quotes words that aren't in the reference"
            }
        }
        // A full-marks grade can't come with a list of serious problems (small models contradict themselves).
        if (output.total == 16 && output.issues.any { it.kind == "mistranslation" || it.kind == "omission" }) {
            problems += "full marks but lists a mistranslation or omission"
        }
        return problems
    }

    companion object {
        val KINDS = listOf("mistranslation", "omission", "addition", "register", "unnatural", "grammar", "term")
        const val MAX_ISSUES = 6

        /** Case- and whitespace-insensitive form used to check quoted spans. */
        internal fun squash(text: String): String = text.lowercase().filterNot { it.isWhitespace() || it == '　' }
    }
}
