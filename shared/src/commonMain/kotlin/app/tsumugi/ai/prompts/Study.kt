package app.tsumugi.ai.prompts

import app.tsumugi.ai.ChatMessage
import app.tsumugi.ai.JsonSchema
import app.tsumugi.ai.PromptTask
import app.tsumugi.ai.Validation
import app.tsumugi.ai.ValidationContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `explain_grammar_in_sentence`: break down the grammar in a sentence the learner tapped. The fallback hook is where
 * the app plugs in the grammar pack's own pattern matcher, so there is always an answer offline without a model.
 */
class ExplainGrammarInSentence(private val fallbackHook: ((Input) -> Output?)? = null) :
    PromptTask<ExplainGrammarInSentence.Input, ExplainGrammarInSentence.Output> {
    data class Input(val sentence: String, val focus: String? = null, val level: String = "N4")

    @Serializable
    data class Point(val pattern: String, val meaning: String, val explanation: String)

    @Serializable
    data class Output(val points: List<Point>)

    override val name = "explain_grammar_in_sentence"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.2
    override val maxTokens = 700
    override val schema = JsonSchema.Obj(
        listOf(
            "points" to JsonSchema.Arr(
                JsonSchema.Obj(
                    listOf(
                        "pattern" to JsonSchema.Str(maxLength = 40),
                        "meaning" to JsonSchema.Str(maxLength = 120),
                        "explanation" to JsonSchema.Str(maxLength = 500),
                    ),
                ),
                minItems = 1,
                maxItems = 5,
            ),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: explain the grammar used in one Japanese sentence to a ${input.level} learner.",
            "List the grammar patterns that appear in the sentence, in order of appearance (at most five).",
            "pattern is the pattern in Japanese as a dictionary would list it (e.g. 〜ている, 〜ので). " +
                "meaning is a short English gloss. explanation says, in English, how it works in this sentence.",
            "Only list patterns that are actually in the sentence.",
        ),
        user(
            buildString {
                append("Sentence: ").append(input.sentence)
                input.focus?.let { append("\nThe learner is asking about: ").append(it) }
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> =
        output.points.flatMapIndexed { i, p ->
            issues(
                Validation.requireJapanese("points[$i].pattern", p.pattern),
                Validation.requireEnglish("points[$i].meaning", p.meaning),
                Validation.requireEnglish("points[$i].explanation", p.explanation),
                if (!appearsIn(p.pattern, input.sentence)) "pattern ${p.pattern} is not in the sentence" else null,
            )
        } + issues(if (output.points.isEmpty()) "no grammar points" else null)

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)

    /** A pattern "appears" if any 1-kana-or-kanji anchor of it (ignoring 〜 and slot markers) is in the sentence. */
    private fun appearsIn(pattern: String, sentence: String): Boolean {
        val anchors = pattern.split('〜', '～', '~', '/', '・', ' ', '（', '）', '(', ')').filter { it.isNotBlank() }
        return anchors.isEmpty() || anchors.any { it in sentence || it.take(1) in sentence }
    }
}

/** `generate_reading_questions`: comprehension questions for a reader passage. Output is AI-generated content. */
class GenerateReadingQuestions : PromptTask<GenerateReadingQuestions.Input, GenerateReadingQuestions.Output> {
    data class Input(val passage: String, val level: String = "N4", val count: Int = 3)

    @Serializable
    data class Question(
        val question: String,
        val choices: List<String>,
        val answer: Int,
        val explanation: String,
    )

    @Serializable
    data class Output(val questions: List<Question>)

    override val name = "generate_reading_questions"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.5
    override val maxTokens = 1200
    override val schema = JsonSchema.Obj(
        listOf(
            "questions" to JsonSchema.Arr(
                JsonSchema.Obj(
                    listOf(
                        "question" to JsonSchema.Str(maxLength = 200),
                        "choices" to JsonSchema.Arr(JsonSchema.Str(maxLength = 80), minItems = 4, maxItems = 4),
                        "answer" to JsonSchema.Integer,
                        "explanation" to JsonSchema.Str(maxLength = 400),
                    ),
                ),
                minItems = 1,
                maxItems = 5,
            ),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: write ${input.count} multiple-choice reading comprehension questions about a Japanese passage for a ${input.level} learner.",
            "Each question is in Japanese and has exactly four Japanese choices; exactly one is correct, and the passage must support it.",
            "answer is the 0-based index of the correct choice. explanation says in English where the passage gives the answer.",
            "Ask about meaning, not trivia; vary which position the correct answer is in.",
        ),
        user("Passage:\n${input.passage}"),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> =
        issues(if (output.questions.size != input.count.coerceIn(1, 5)) "expected ${input.count} questions" else null) +
            output.questions.flatMapIndexed { i, q ->
                issues(
                    Validation.requireJapanese("questions[$i].question", q.question),
                    if (q.choices.size != 4) "questions[$i] needs four choices" else null,
                    if (q.choices.map { it.trim() }.toSet().size != q.choices.size) "questions[$i] has duplicate choices" else null,
                    q.choices.firstNotNullOfOrNull { Validation.scriptLeak(it) }?.let { "questions[$i] choice $it" },
                    if (q.answer !in q.choices.indices) "questions[$i].answer is out of range" else null,
                    Validation.requireEnglish("questions[$i].explanation", q.explanation),
                )
            }
}

/** `suggest_mnemonic`: an original memory hook for a kanji or word (never copied from other apps, BRIEF §2). */
class SuggestMnemonic : PromptTask<SuggestMnemonic.Input, SuggestMnemonic.Output> {
    data class Input(
        val item: String,
        val meanings: List<String>,
        val readings: List<String>,
        /** Component/radical names for kanji, empty for words. */
        val components: List<String> = emptyList(),
    )

    @Serializable
    data class Output(
        @SerialName("meaning_mnemonic") val meaningMnemonic: String,
        @SerialName("reading_mnemonic") val readingMnemonic: String,
    )

    override val name = "suggest_mnemonic"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.8
    override val maxTokens = 400
    override val schema = JsonSchema.Obj(
        listOf(
            "meaning_mnemonic" to JsonSchema.Str(maxLength = 500),
            "reading_mnemonic" to JsonSchema.Str(maxLength = 500),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: invent short, vivid English mnemonics to remember a Japanese item. Write something original.",
            "meaning_mnemonic: a story or image that ties the item${if (input.components.isEmpty()) "" else " (built from its components)"} to its meaning, and names the meaning.",
            "reading_mnemonic: a sound-alike hook for the reading. Two or three sentences each at most.",
        ),
        user(
            buildString {
                append("Item: ").append(input.item)
                append("\nMeanings: ").append(input.meanings.joinToString(", "))
                append("\nReadings: ").append(input.readings.joinToString(", "))
                if (input.components.isNotEmpty()) append("\nComponents: ").append(input.components.joinToString(", "))
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        Validation.requireEnglish("meaning_mnemonic", output.meaningMnemonic),
        Validation.requireEnglish("reading_mnemonic", output.readingMnemonic),
        Validation.length("meaning_mnemonic", output.meaningMnemonic, min = 15, max = 500),
        Validation.length("reading_mnemonic", output.readingMnemonic, min = 10, max = 500),
        if (!mentionsMeaning(output.meaningMnemonic, input.meanings)) "meaning_mnemonic doesn't mention the meaning" else null,
    )

    private fun mentionsMeaning(text: String, meanings: List<String>): Boolean {
        val lower = text.lowercase()
        val words = meanings.flatMap { it.lowercase().split(' ', ',', ';', '(', ')') }
            .filter { it.length >= 3 && it !in STOP_WORDS }
        return words.isEmpty() || words.any { it in lower }
    }

    private companion object {
        val STOP_WORDS = setOf("the", "and", "for", "something", "someone", "one's")
    }
}

/** `jlpt_explain_item`: why the right answer of a practice test item is right (and the learner's pick is wrong). */
class JlptExplainItem(private val fallbackHook: ((Input) -> Output?)? = null) : PromptTask<JlptExplainItem.Input, JlptExplainItem.Output> {
    data class Input(
        val level: String,
        val question: String,
        val choices: List<String>,
        val correctIndex: Int,
        val chosenIndex: Int? = null,
        /** Passage or audio script the item refers to, if any. */
        val stimulus: String? = null,
    )

    @Serializable
    data class Output(
        val explanation: String,
        @SerialName("why_wrong") val whyWrong: String = "",
        @SerialName("key_point") val keyPoint: String,
    )

    override val name = "jlpt_explain_item"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.2
    override val maxTokens = 600
    override val schema = JsonSchema.Obj(
        listOf(
            "explanation" to JsonSchema.Str(maxLength = 800),
            "why_wrong" to JsonSchema.Str(maxLength = 500),
            "key_point" to JsonSchema.Str(maxLength = 200),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: explain a ${input.level} practice test item to a learner, in English.",
            "The correct answer is given; do not dispute it. explanation says why it is correct and quotes it.",
            "why_wrong explains why the learner's choice doesn't fit (empty if they chose correctly or didn't answer).",
            "key_point is the one thing to remember, in a single short sentence.",
        ),
        user(
            buildString {
                input.stimulus?.let { append("Passage/script:\n").append(it).append("\n\n") }
                append("Question: ").append(input.question)
                input.choices.forEachIndexed { i, c -> append('\n').append(i + 1).append(". ").append(c) }
                append("\nCorrect answer: ").append(input.correctIndex + 1).append(". ").append(input.choices[input.correctIndex])
                input.chosenIndex?.let { append("\nLearner chose: ").append(it + 1).append(". ").append(input.choices[it]) }
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        Validation.requireEnglish("explanation", output.explanation),
        Validation.length("explanation", output.explanation, min = 20, max = 800),
        Validation.requireEnglish("key_point", output.keyPoint),
        if (input.choices[input.correctIndex].trim() !in output.explanation) "explanation doesn't quote the correct answer" else null,
        if (input.chosenIndex != null && input.chosenIndex != input.correctIndex && output.whyWrong.isBlank()) {
            "why_wrong is missing"
        } else {
            null
        },
    )

    override fun fallback(input: Input): Output? = fallbackHook?.invoke(input)
}
