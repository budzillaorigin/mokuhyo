package app.tsumugi.tracks

import app.tsumugi.jp.Kana
import app.tsumugi.jp.WordClass
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.practice.Register
import app.tsumugi.practice.Speaker
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Drill types stored in `track_drill.type`. */
enum class DrillType(val code: String) {
    KEIGO("keigo"), EMAIL("email"), FILL_IN("fill_in"), SYNONYM("synonym"), USAGE("usage"), MEANING("meaning"), PERFORM("perform");

    companion object {
        fun of(code: String): DrillType? = entries.firstOrNull { it.code == code }
    }
}

/** The outcome of one answer: whether it counts, what was expected, and why. */
data class DrillResult(val correct: Boolean, val expected: List<String>, val explanation: String, val given: String = "")

/** A track drill. Every drill carries its source; "llm" shows the AI-generated badge (CLAUDE.md rule 10). */
sealed interface TrackDrill {
    val id: String
    val trackId: String
    val type: DrillType
    val topic: String
    val jlpt: Int?
    val source: String
    val isAiGenerated: Boolean get() = source == "llm"
}

/**
 * Plain → 尊敬語/謙譲語/丁寧語. [check] accepts the authored [answers] plus every form [KeigoRules] derives for [verb]
 * in [target]/[form], compared after [AnswerText.normalize].
 */
data class KeigoDrill(
    override val id: String,
    override val trackId: String,
    override val topic: String,
    override val jlpt: Int?,
    override val source: String,
    val plain: String,
    val target: KeigoTarget,
    val form: KeigoForm,
    /** Context sentence with one （　　）, or null for a bare transformation. */
    val sentence: String?,
    val english: String?,
    val answers: List<String>,
    val explanation: String,
    /** The verb the rules conjugate (plain itself, or 知る for 知っている), with its reading and class; null if not a verb. */
    val verb: String?,
    val verbReading: String?,
    val verbClass: WordClass?,
) : TrackDrill {
    override val type get() = DrillType.KEIGO

    /** Forms the rule engine accepts (may be empty for non-verbs such as だ → でございます). */
    val ruleForms: Set<String> get() = verb?.let { KeigoRules.forms(it, verbReading, verbClass, target, form) }.orEmpty()

    fun check(answer: String): DrillResult {
        val given = AnswerText.normalize(answer)
        val ok = given.isNotEmpty() && (answers.asSequence() + ruleForms.asSequence()).any { AnswerText.normalize(it) == given }
        return DrillResult(ok, answers, explanation, answer)
    }
}

/** One blank in an email template. */
@Serializable
data class EmailBlank(val answers: List<String>, val choices: List<String>? = null, val hint: String = "")

/** A business email with fill-ins; the body holds ｛1｝…｛n｝ slots. */
data class EmailDrill(
    override val id: String,
    override val trackId: String,
    override val topic: String,
    override val jlpt: Int?,
    override val source: String,
    val title: String,
    val situation: String,
    val subject: String,
    val body: String,
    val blanks: List<EmailBlank>,
    val english: String?,
) : TrackDrill {
    override val type get() = DrillType.EMAIL

    /** The body split into text runs and slots, in order (for rendering blanks inline). */
    val segments: List<EmailSegment>
        get() = buildList {
            var at = 0
            for (m in SLOT.findAll(body)) {
                if (m.range.first > at) add(EmailSegment.Text(body.substring(at, m.range.first)))
                add(EmailSegment.Slot(m.groupValues[1].toInt() - 1))
                at = m.range.last + 1
            }
            if (at < body.length) add(EmailSegment.Text(body.substring(at)))
        }

    fun check(blank: Int, answer: String): DrillResult {
        val b = blanks[blank]
        val given = AnswerText.normalize(answer)
        return DrillResult(given.isNotEmpty() && b.answers.any { AnswerText.normalize(it) == given }, b.answers, b.hint, answer)
    }

    /** The whole email with the first answer in every slot (the model answer). */
    val filled: String get() = SLOT.replace(body) { blanks[it.groupValues[1].toInt() - 1].answers.first() }

    companion object {
        private val SLOT = Regex("｛(\\d+)｝")
    }
}

sealed interface EmailSegment {
    data class Text(val text: String) : EmailSegment
    data class Slot(val blank: Int) : EmailSegment
}

/** A sentence with one （　　）: typed or picked from [choices]. */
data class FillInDrill(
    override val id: String,
    override val trackId: String,
    override val topic: String,
    override val jlpt: Int?,
    override val source: String,
    val sentence: String,
    val answers: List<String>,
    val choices: List<String>?,
    val word: String?,
    val entryId: Long?,
    val english: String,
    val explanation: String,
) : TrackDrill {
    override val type get() = DrillType.FILL_IN

    /** The sentence around the blank: (before, after). */
    val parts: Pair<String, String> get() = sentence.substringBefore(BLANK) to sentence.substringAfter(BLANK)

    /** The sentence with the first answer filled in. */
    val completed: String get() = sentence.replace(BLANK, answers.first())

    fun check(answer: String): DrillResult {
        val given = AnswerText.normalize(answer)
        return DrillResult(given.isNotEmpty() && answers.any { AnswerText.normalize(it) == given }, answers, explanation, answer)
    }

    companion object {
        const val BLANK = "（　　）"
    }
}

/** Pick the synonym (or antonym) of [word]. */
data class SynonymDrill(
    override val id: String,
    override val trackId: String,
    override val topic: String,
    override val jlpt: Int?,
    override val source: String,
    val antonym: Boolean,
    val word: String,
    val entryId: Long,
    val choices: List<String>,
    val answer: Int,
    val explanation: String,
) : TrackDrill {
    override val type get() = DrillType.SYNONYM
    fun check(choice: Int): DrillResult = DrillResult(choice == answer, listOf(choices[answer]), explanation, choices.getOrElse(choice) { "" })
}

/** Is [word] used correctly in [sentence]? */
data class UsageDrill(
    override val id: String,
    override val trackId: String,
    override val topic: String,
    override val jlpt: Int?,
    override val source: String,
    val word: String,
    val entryId: Long,
    val sentence: String,
    val correct: Boolean,
    val explanation: String,
) : TrackDrill {
    override val type get() = DrillType.USAGE
    fun check(saysCorrect: Boolean): DrillResult = DrillResult(saysCorrect == correct, listOf(if (correct) "○" else "×"), explanation, if (saysCorrect) "○" else "×")
}

/** Pick the meaning of an idiom, proverb, onomatopoeia or domain word. */
data class MeaningDrill(
    override val id: String,
    override val trackId: String,
    override val topic: String,
    override val jlpt: Int?,
    override val source: String,
    val word: String,
    val entryId: Long,
    val choices: List<String>,
    val answer: Int,
    val explanation: String,
) : TrackDrill {
    override val type get() = DrillType.MEANING
    fun check(choice: Int): DrillResult = DrillResult(choice == answer, listOf(choices[answer]), explanation, choices.getOrElse(choice) { "" })
}

/** One line of a performance, with an optional staging cue ("bows 30°, then hands over the gift with both hands"). */
@Serializable
data class PerformLine(val speaker: String, val ja: String, val en: String, val stage: String? = null)

/** A "performance": a scripted exchange with staging notes, learned in memorize-and-perform mode ([PerformanceSession]). */
data class PerformDrill(
    override val id: String,
    override val trackId: String,
    override val topic: String,
    override val jlpt: Int?,
    override val source: String,
    val title: String,
    val titleJa: String,
    val setting: String,
    val register: Register,
    val speakers: List<Speaker>,
    /** Speaker id the learner plays. */
    val learner: String,
    val staging: List<String>,
    val lines: List<PerformLine>,
) : TrackDrill {
    override val type get() = DrillType.PERFORM
    val learnerLines: List<Int> get() = lines.indices.filter { lines[it].speaker == learner }
    fun speaker(id: String): Speaker? = speakers.firstOrNull { it.id == id }
}

/** How typed answers are compared: NFC, no spaces or punctuation, katakana folded to hiragana (rule 7: search-style). */
object AnswerText {
    private val DROP = setOf('。', '、', '．', '，', '！', '？', '!', '?', '.', ',', '「', '」', '　', '・', '〜', '~')

    fun normalize(s: String): String = Kana.toHiragana(normalizeNfc(s).trim()).filterNot { it.isWhitespace() || it in DROP }
}

/** Parses `track_drill.payload` JSON into a [TrackDrill]; null for unknown types (newer packs). */
internal object DrillPayloads {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Keigo(
        val plain: String, val target: String, val form: String = "masu", val sentence: String? = null, val en: String? = null,
        val answers: List<String>, val explanation: String, val verb: String? = null, val verbReading: String? = null,
        val verbClass: String? = null,
    )

    @Serializable
    private data class Email(
        val title: String, val situation: String, val subject: String, val body: String, val blanks: List<EmailBlank>, val en: String? = null,
    )

    @Serializable
    private data class FillIn(
        val sentence: String, val answers: List<String>, val choices: List<String>? = null, val word: String? = null,
        val entryId: Long? = null, val en: String, val explanation: String,
    )

    @Serializable
    private data class Choice(
        val word: String, val entryId: Long, val choices: List<String>, val answer: Int, val explanation: String, val relation: String = "synonym",
    )

    @Serializable
    private data class Usage(val word: String, val entryId: Long, val sentence: String, val correct: Boolean, val explanation: String)

    @Serializable
    private data class Perform(
        val title: String, val titleJa: String, val setting: String, val register: String, val speakers: List<Speaker>,
        val learner: String, val staging: List<String>, val lines: List<PerformLine>,
    )

    fun parse(id: String, trackId: String, type: String, topic: String, jlpt: Int?, payload: String, source: String): TrackDrill? =
        when (DrillType.of(type)) {
            DrillType.KEIGO -> json.decodeFromString<Keigo>(payload).let { p ->
                KeigoDrill(
                    id, trackId, topic, jlpt, source, p.plain, KeigoTarget.of(p.target) ?: return null, KeigoForm.of(p.form) ?: return null,
                    p.sentence, p.en, p.answers, p.explanation, p.verb, p.verbReading,
                    p.verbClass?.let { c -> runCatching { WordClass.valueOf(c) }.getOrNull() },
                )
            }
            DrillType.EMAIL -> json.decodeFromString<Email>(payload).let { p ->
                EmailDrill(id, trackId, topic, jlpt, source, p.title, p.situation, p.subject, p.body, p.blanks, p.en)
            }
            DrillType.FILL_IN -> json.decodeFromString<FillIn>(payload).let { p ->
                FillInDrill(id, trackId, topic, jlpt, source, p.sentence, p.answers, p.choices, p.word, p.entryId, p.en, p.explanation)
            }
            DrillType.SYNONYM -> json.decodeFromString<Choice>(payload).let { p ->
                SynonymDrill(id, trackId, topic, jlpt, source, p.relation == "antonym", p.word, p.entryId, p.choices, p.answer, p.explanation)
            }
            DrillType.MEANING -> json.decodeFromString<Choice>(payload).let { p ->
                MeaningDrill(id, trackId, topic, jlpt, source, p.word, p.entryId, p.choices, p.answer, p.explanation)
            }
            DrillType.USAGE -> json.decodeFromString<Usage>(payload).let { p ->
                UsageDrill(id, trackId, topic, jlpt, source, p.word, p.entryId, p.sentence, p.correct, p.explanation)
            }
            DrillType.PERFORM -> json.decodeFromString<Perform>(payload).let { p ->
                PerformDrill(
                    id, trackId, topic, jlpt, source, p.title, p.titleJa, p.setting,
                    runCatching { Register.valueOf(p.register.uppercase()) }.getOrDefault(Register.POLITE),
                    p.speakers, p.learner, p.staging, p.lines,
                )
            }
            null -> null
        }
}
