package app.tsumugi.ai.prompts

import app.tsumugi.ai.ChatMessage
import app.tsumugi.ai.JsonSchema
import app.tsumugi.ai.PromptTask
import app.tsumugi.ai.Validation
import app.tsumugi.ai.ValidationContext
import app.tsumugi.jp.Kana
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * `paraphrase_word_ja` (BRIEF_V2 §6.6 monolingual mode, DECISIONS D-234): a Japanese-only definition of a dictionary
 * word, like a 国語辞典 entry, because JMdict has no Japanese glosses. The English glosses are given only so the model
 * picks the right sense. The output is AI-generated (badge) and cached per word by `WordParaphrases`.
 * There is no deterministic fallback: without a model the app shows the English glosses and says why.
 */
class ParaphraseWordJa : PromptTask<ParaphraseWordJa.Input, ParaphraseWordJa.Output> {
    data class Input(
        val word: String,
        val reading: String,
        /** English glosses of the sense to paraphrase (JMdict), most important first. */
        val glosses: List<String>,
        /** JMdict part-of-speech codes of that sense (v5r, adj-i, n, …). */
        val partOfSpeech: List<String> = emptyList(),
        /** The learner's level, which sets how hard the Japanese of the definition may be ("N2"). */
        val level: String = "N2",
    )

    @Serializable
    data class Output(
        /** 国語辞典-style definition in Japanese. */
        val paraphrase: String,
        /** One natural example sentence that uses the word. */
        val example: String = "",
        /** Optional usage note in Japanese (register, typical collocations); "" when none. */
        val note: String = "",
    )

    override val name = "paraphrase_word_ja"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.2
    override val maxTokens = 300
    override val schema = JsonSchema.Obj(
        listOf(
            "paraphrase" to JsonSchema.Str(maxLength = MAX_PARAPHRASE * 2),
            "example" to JsonSchema.Str(maxLength = 160),
            "note" to JsonSchema.Str(maxLength = 160),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: write a Japanese-only definition of a Japanese word for a monolingual dictionary view, like a 国語辞典 entry.",
            "This task is Japanese-only: write every field in Japanese (no English, no romaji), overriding the plain-English rule above.",
            "paraphrase: one or two short sentences that explain the meaning in simpler Japanese than the word itself, suitable for a ${input.level} learner. " +
                "Do not use the word itself in its own definition.",
            "example: one natural example sentence that uses the word. note: register or typical collocations, or \"\" if nothing useful.",
            "The English glosses only tell you which sense to define; do not translate them word for word.",
        ),
        user(
            buildString {
                append("Word: ").append(input.word)
                if (input.reading.isNotBlank() && input.reading != input.word) append(" (").append(input.reading).append(")")
                if (input.partOfSpeech.isNotEmpty()) append("\nPart of speech: ").append(input.partOfSpeech.joinToString(", "))
                append("\nEnglish glosses (sense to define): ").append(input.glosses.joinToString("; "))
                append("\nLearner level: ").append(input.level)
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        Validation.requireJapanese("paraphrase", output.paraphrase),
        latinLetters("paraphrase", output.paraphrase),
        Validation.length("paraphrase", output.paraphrase, min = 4, max = MAX_PARAPHRASE),
        if (circular(input, output.paraphrase)) "paraphrase uses the word itself" else null,
        output.example.takeIf { it.isNotBlank() }?.let { Validation.requireJapanese("example", it) },
        if (output.example.isNotBlank() && !usesWord(input, output.example)) "example doesn't use the word" else null,
        output.note.takeIf { it.isNotBlank() }?.let { latinLetters("note", it) ?: Validation.requireJapanese("note", it) },
    )

    /** Monolingual text allows no Latin words at all (acronyms included would still read as English glosses). */
    private fun latinLetters(field: String, text: String): String? =
        if (Regex("[A-Za-z]{2,}").containsMatchIn(text)) "$field contains English" else null

    /**
     * A definition that repeats a headword of 2+ characters explains nothing (one-character words are exempt). For
     * kana-only words the check folds katakana to hiragana, so ドキドキ is caught as どきどき too.
     */
    private fun circular(input: Input, text: String): Boolean {
        if (input.word.length < 2) return false
        if (input.word in text) return true
        return Kana.isAllKana(input.word) && Kana.toHiragana(input.word) in Kana.toHiragana(text)
    }

    /** The example contains the word, its reading, or its stem (conjugated verbs and adjectives drop the last kana). */
    private fun usesWord(input: Input, text: String): Boolean {
        val folded = Kana.toHiragana(text)
        val forms = listOf(input.word, input.reading).filter { it.isNotBlank() }
        return forms.any { f ->
            f in text || Kana.toHiragana(f) in folded || (f.length >= 2 && Kana.toHiragana(f.dropLast(1)) in folded)
        }
    }

    companion object {
        const val MAX_PARAPHRASE = 120
    }
}
