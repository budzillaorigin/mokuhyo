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

/**
 * `grade_reading_summary`: grades the learner's Japanese summary of a graded reader (the genre task's post-reading
 * output, BRIEF_V2 §6.4, DECISIONS D-204). The grade is AI-generated and labeled as such (rule 10).
 */
class GradeReadingSummary : PromptTask<GradeReadingSummary.Input, GradeReadingSummary.Output> {
    data class Input(
        val passage: String,
        val summary: String,
        val level: String = "N4",
        /** What the task asked for, e.g. "Summarize the article in Japanese, in about 80–160 characters." */
        val instruction: String = "",
    )

    @Serializable
    data class Output(
        /** 0 = misses the main points, 1 = some of them, 2 = the main points. */
        val content: Int,
        /** 0 = says things the text doesn't, 1 = a small slip, 2 = faithful. */
        val accuracy: Int,
        /** 0 = hard to follow, 1 = understandable with errors, 2 = clear for the level. */
        val language: Int,
        /** A lightly corrected version of the summary ("" when it needs no change). */
        val corrected: String = "",
        /** One to three sentences in English. */
        val feedback: String,
    ) {
        val total: Int get() = content + accuracy + language
    }

    override val name = "grade_reading_summary"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.1
    override val maxTokens = 500
    override val schema = JsonSchema.Obj(
        listOf(
            "content" to JsonSchema.Integer,
            "accuracy" to JsonSchema.Integer,
            "language" to JsonSchema.Integer,
            "corrected" to JsonSchema.Str(maxLength = 600),
            "feedback" to JsonSchema.Str(maxLength = 500),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> = listOf(
        system(
            "Task: grade a ${input.level} learner's Japanese summary of a Japanese text they just read.",
            "Score three things from 0 to 2: content (does it give the text's main points), accuracy (does it say only " +
                "what the text says), language (is the Japanese clear for a ${input.level} learner; small slips are fine).",
            "corrected is the learner's summary with the fewest changes that fix its Japanese, or \"\" if it needs none.",
            "feedback is one to three sentences in English: what is good and what to add or fix.",
        ),
        user(
            buildString {
                if (input.instruction.isNotBlank()) append("Task given to the learner: ").append(input.instruction).append('\n')
                append("Text:\n").append(input.passage).append("\n\n")
                append("Learner's summary: ").append(input.summary)
            },
        ),
    )

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> = issues(
        listOf(output.content, output.accuracy, output.language).takeIf { s -> s.any { it !in 0..2 } }?.let { "scores must be 0 to 2" },
        Validation.requireEnglish("feedback", output.feedback),
        Validation.length("feedback", output.feedback, min = 10, max = 500),
        output.corrected.takeIf { it.isNotBlank() }?.let { Validation.requireJapanese("corrected", it) },
    )
}
