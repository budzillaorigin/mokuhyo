package app.mokuhyo.exam

import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.PromptTask
import app.mokuhyo.ai.Role
import app.mokuhyo.ai.ValidationContext
import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.ScriptCheck
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * `generate_passage` (BRIEF §5.4 "Generate more"): the local model drafts one practice passage with English
 * multiple-choice questions, checked with the same rules as the shipped banks (target-language text, English questions,
 * four distinct choices, length for the level). The result goes to the "Generated on this computer" bank, never mixed
 * into shipped-bank statistics.
 */
class GeneratePassage : PromptTask<GeneratePassage.Input, GeneratePassage.Output> {
    data class Input(
        val language: String,
        val skill: Skill,
        val level: String,
        val textType: String,
        val topic: String,
        /** Word count of the language's segmenter scaled to English-equivalent words (for the length check). */
        val wordCount: (String) -> Int,
        val items: Int = 3,
    )

    @Serializable
    data class Line(val speaker: String, val voice: String, val text: String)

    @Serializable
    data class Question(val type: String, val stem: String, val choices: List<String>, val answer: Int, val explanation: String)

    @Serializable
    data class Output(val title: String, val body: String = "", val script: List<Line> = emptyList(), val questions: List<Question>)

    override val name = "generate_passage"
    override val serializer: KSerializer<Output> = Output.serializer()
    override val temperature = 0.7
    override val maxTokens = 2200

    private val question = JsonSchema.Obj(
        listOf(
            "type" to JsonSchema.Str(enum = TYPES),
            "stem" to JsonSchema.Str(maxLength = 240),
            "choices" to JsonSchema.Arr(JsonSchema.Str(maxLength = 160), maxItems = 4),
            "answer" to JsonSchema.Integer,
            "explanation" to JsonSchema.Str(maxLength = 400),
        ),
    )

    override val schema = JsonSchema.Obj(
        listOf(
            "title" to JsonSchema.Str(maxLength = 120),
            "body" to JsonSchema.Str(maxLength = 4000),
            "script" to JsonSchema.Arr(
                JsonSchema.Obj(listOf("speaker" to JsonSchema.Str(maxLength = 40), "voice" to JsonSchema.Str(enum = listOf("female", "male")), "text" to JsonSchema.Str(maxLength = 600))),
                maxItems = 16,
            ),
            "questions" to JsonSchema.Arr(question, maxItems = 4),
        ),
    )

    override fun messages(input: Input): List<ChatMessage> {
        val lang = Languages.of(input.language)?.nameEnglish ?: input.language
        val (lo, hi) = WORDS.getValue(input.skill).getValue(input.level)
        val form = if (input.skill == Skill.READING) "Write the text in \"body\" (leave \"script\" empty)." else
            "Write a listening script in \"script\" (speaker label in $lang, voice female/male, spoken text); leave \"body\" empty."
        return listOf(
            ChatMessage(Role.SYSTEM, "You write original practice material for foreign-language proficiency tests on the ILR scale. Never copy real test items; invent plausible names and places."),
            ChatMessage(Role.USER, """Language: $lang. ILR level ${input.level}. Text type: ${input.textType.replace('_', ' ')}. Topic: ${input.topic}.
Length: $lo–$hi words. $form Write the text only in $lang (no English words).
Then ${input.items} multiple-choice questions IN ENGLISH (types: ${TYPES.joinToString()}), each with exactly 4 English choices,
one correct (answer = 0-based index), plausible distractors, and an English explanation quoting the text. Never translate the
tested word in a vocabulary question. Give a short English title."""),
        )
    }

    override fun validate(input: Input, output: Output, context: ValidationContext): List<String> {
        val problems = ArrayList<String>()
        val text = if (input.skill == Skill.READING) output.body else output.script.joinToString("\n") { it.text }
        ScriptCheck.requireLanguage("text", text, input.language)?.let { problems += it }
        if (input.skill == Skill.LISTENING && output.script.isEmpty()) problems += "listening needs a script"
        val (lo, hi) = WORDS.getValue(input.skill).getValue(input.level)
        val words = input.wordCount(text)
        if (words < lo * 0.8 || words > hi * 1.25) problems += "length $words words is outside $lo–$hi"
        if (output.questions.size != input.items) problems += "needs ${input.items} questions"
        output.questions.forEach { q ->
            if (q.choices.size != 4 || q.choices.map { it.trim().lowercase() }.toSet().size != 4) problems += "each question needs 4 distinct choices"
            if (q.answer !in 0..3) problems += "answer index out of range"
            ScriptCheck.requireEnglish("stem", q.stem)?.let { problems += it }
            if (q.type !in TYPES) problems += "unknown question type ${q.type}"
        }
        return problems.distinct()
    }

    /** The passage and items for the local bank (ids prefixed "local-", bank "local", source "llm"). */
    fun toBank(input: Input, output: Output, id: String): Pair<ExamPassage, List<ExamItem>> {
        val exam = input.skill.exam
        val passage = ExamPassage(
            id = id, exam = exam, language = input.language, level = input.level, textType = input.textType, title = output.title,
            body = output.body, script = output.script.map { ScriptLine(it.speaker, it.voice, it.text) }, source = "llm", verified = false, bank = "local",
        )
        val items = output.questions.mapIndexed { i, q ->
            ExamItem("$id-q${i + 1}", "local", exam, input.level, q.type, id, q.stem, q.choices, q.answer, q.explanation, emptyList(), emptyList(), "llm", false)
        }
        return passage to items
    }

    companion object {
        val TYPES = listOf("main_idea", "detail", "inference", "purpose", "vocabulary_in_context", "tone")

        /** English-equivalent word ranges per level (tools/items/ilr_bands.json). */
        val WORDS: Map<Skill, Map<String, Pair<Int, Int>>> = mapOf(
            Skill.READING to mapOf("0+" to (6 to 70), "1" to (15 to 130), "1+" to (50 to 200), "2" to (100 to 320), "2+" to (150 to 420), "3" to (200 to 560)),
            Skill.LISTENING to mapOf("0+" to (6 to 60), "1" to (15 to 100), "1+" to (40 to 160), "2" to (80 to 260), "2+" to (110 to 340), "3" to (150 to 420)),
        )
    }
}
