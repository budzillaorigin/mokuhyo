package app.tsumugi.ai.prompts

import app.tsumugi.ai.ChatMessage
import app.tsumugi.ai.JsonSchema
import app.tsumugi.ai.PromptTask
import app.tsumugi.ai.Validation
import app.tsumugi.ai.ValidationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `correct_sentence`: grammar correction of a learner's sentence with a minimal edit. */
class CorrectSentence(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<CorrectSentence.Input, CorrectSentence.Output> {
    data class Input(val sentence: String, val level: String = "N4", val context: String? = null)

    @Serializable
    data class Edit(val original: String, val replacement: String, val reason: String)

    @Serializable
    data class Output(
        @SerialName("is_correct") val isCorrect: Boolean,
        val corrected: String,
        val confidence: Double,
        val edits: List<Edit> = emptyList(),
        val explanation: String = "",
    ) {
        /** Below 0.5 the UI says "not sure" instead of marking the learner right or wrong. */
        val isUnsure: Boolean get() = confidence < 0.5
    }

    override val name = "correct_sentence"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.1
    override val schema = JsonSchema.Obj(
        listOf(
            "is_correct" to JsonSchema.Bool,
            "corrected" to JsonSchema.Str(maxLength = 300),
            "confidence" to JsonSchema.Num,
            "edits" to JsonSchema.Arr(
                JsonSchema.Obj(
                    listOf(
                        "original" to JsonSchema.Str(maxLength = 80),
                        "replacement" to JsonSchema.Str(maxLength = 80),
                        "reason" to JsonSchema.Str(maxLength = 300),
                    ),
                ),
                maxItems = 6,
            ),
            "explanation" to JsonSchema.Str(maxLength = 600),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: check one sentence written by a ${input.level} learner of Japanese.",
            "If it is grammatical and natural enough, set is_correct to true and copy it unchanged into corrected.",
            "Otherwise fix only what is wrong, changing as little as possible: keep the learner's words, style and meaning.",
            "List each change in edits (original fragment, replacement, a short reason in English).",
            "confidence is 0 to 1: how sure you are of your verdict. Use a low value for dialect, casual speech or anything you are unsure about.",
        ),
        user(
            buildString {
                input.context?.let { append("Context: ").append(it).append('\n') }
                append("Sentence: ").append(input.sentence)
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        Validation.requireJapanese("corrected", output.corrected),
        Validation.minimalEdit(input.sentence, output.corrected),
        if (output.confidence !in 0.0..1.0) "confidence must be between 0 and 1" else null,
        if (output.isCorrect && output.corrected.trim() != input.sentence.trim()) "marked correct but the sentence was changed" else null,
        if (!output.isCorrect && output.edits.isEmpty()) "marked incorrect but no edits were listed" else null,
        if (!output.isCorrect && output.corrected.trim() == input.sentence.trim()) "marked incorrect but nothing was changed" else null,
        output.edits.firstOrNull { it.replacement.isNotBlank() && it.replacement !in output.corrected }
            ?.let { "edit \"${it.replacement}\" is not in the corrected sentence" },
        output.explanation.takeIf { it.isNotBlank() }?.let { Validation.requireEnglish("explanation", it) },
        if (!context.isKnownJapanese(output.corrected)) "corrected sentence contains words the dictionary doesn't know" else null,
    )

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)
}

/** `natural_rewrite`: say the same thing the way a native speaker would, in a chosen register. */
class NaturalRewrite(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<NaturalRewrite.Input, NaturalRewrite.Output> {
    enum class Register { CASUAL, POLITE, FORMAL }

    data class Input(val sentence: String, val register: Register = Register.POLITE)

    @Serializable
    data class Output(val rewrite: String, val notes: String = "")

    override val name = "natural_rewrite"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.4
    override val schema = JsonSchema.Obj(
        listOf("rewrite" to JsonSchema.Str(maxLength = 300), "notes" to JsonSchema.Str(maxLength = 600)),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: rewrite a learner's Japanese sentence so it sounds natural to a native speaker, keeping the meaning.",
            "Register: ${registerHint(input.register)}.",
            "In notes, briefly explain in English what you changed and why.",
        ),
        user("Sentence: ${input.sentence}"),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        Validation.requireJapanese("rewrite", output.rewrite),
        Validation.length("rewrite", output.rewrite, max = maxOf(40, input.sentence.length * 3)),
        output.notes.takeIf { it.isNotBlank() }?.let { Validation.requireEnglish("notes", it) },
        if (!context.isKnownJapanese(output.rewrite)) "rewrite contains words the dictionary doesn't know" else null,
    )

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)

    private fun registerHint(r: Register) = when (r) {
        Register.CASUAL -> "casual plain form, as between friends"
        Register.POLITE -> "polite です/ます form"
        Register.FORMAL -> "formal business Japanese with appropriate keigo"
    }
}

/** `translate_sentence`: Japanese↔English translation with an optional literal gloss. */
class TranslateSentence(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<TranslateSentence.Input, TranslateSentence.Output> {
    enum class Direction { JA_TO_EN, EN_TO_JA }

    data class Input(val text: String, val direction: Direction = Direction.JA_TO_EN)

    @Serializable
    data class Output(val translation: String, val literal: String = "", val notes: String = "")

    override val name = "translate_sentence"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.2
    override val schema = JsonSchema.Obj(
        listOf(
            "translation" to JsonSchema.Str(maxLength = 600),
            "literal" to JsonSchema.Str(maxLength = 600),
            "notes" to JsonSchema.Str(maxLength = 400),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        if (input.direction == Direction.JA_TO_EN) {
            system(
                "Task: translate the Japanese text into natural English.",
                "In literal, give a word-by-word English gloss that shows the Japanese structure. Put nuance (politeness, omitted subjects) in notes.",
            )
        } else {
            system(
                "Task: translate the English text into natural Japanese (polite です/ます form unless the text is clearly casual).",
                "In literal, give an English back-translation of your Japanese. Put alternatives or nuance in notes, in English.",
            )
        },
        user("Text: ${input.text}"),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        if (input.direction == Direction.EN_TO_JA) {
            Validation.requireJapanese("translation", output.translation)
        } else {
            Validation.requireEnglish("translation", output.translation)
        },
        Validation.length("translation", output.translation, max = maxOf(60, input.text.length * 4)),
        if (input.direction == Direction.EN_TO_JA && !context.isKnownJapanese(output.translation)) {
            "translation contains words the dictionary doesn't know"
        } else {
            null
        },
    )

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)
}
