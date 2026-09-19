package app.tsumugi.translation

import app.tsumugi.jp.Kana
import app.tsumugi.review.reviewKey
import kotlin.math.ceil

/** J→E (the text is Japanese) or E→J. */
enum class TranslationDirection(val code: String) {
    JE("JE"), EJ("EJ");

    val japaneseSource: Boolean get() = this == JE

    companion object {
        fun of(code: String): TranslationDirection = entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: JE
    }
}

/** The workbench genres (BRIEF_V2 §6.12). */
enum class TranslationGenre(val code: String, val label: String) {
    NEWS("news", "News"),
    TECHNICAL("technical", "Technical"),
    LEGAL("legal", "Legal"),
    LITERARY("literary", "Literary"),
    DIALOGUE("dialogue", "Dialogue"),
    MILITARY("military", "Military"),
    ;

    companion object {
        fun of(code: String): TranslationGenre? = entries.firstOrNull { it.code == code }
    }
}

/** Sight translation (spoken, captured by STT) or written. */
enum class TranslationMode { SIGHT, WRITTEN }

/** Where a passage came from (D-270). */
enum class PassageOrigin(val code: String) {
    ORIGINAL("original"), READER("reader"), AOZORA("aozora"), TATOEBA("tatoeba"), USER("user");

    companion object {
        fun of(code: String): PassageOrigin = entries.firstOrNull { it.code == code } ?: ORIGINAL
    }
}

data class TranslationPassage(
    val id: String,
    val direction: TranslationDirection,
    /** A [TranslationGenre] code; imported text may use any genre. */
    val genre: String,
    /** The Japanese side's JLPT label, N5…N1. */
    val level: String,
    val ilr: String,
    val title: String,
    val text: String,
    /** The model answer; "" for imported text. */
    val reference: String,
    val register: String,
    val keyPoints: List<String>,
    val notes: String,
    val origin: PassageOrigin,
    /** Graded-reader story id, Aozora work id or Tatoeba sentence ids. */
    val originRef: String?,
    val sightSeconds: Int,
    /** llm | verified | tatoeba | user. */
    val source: String,
) {
    /** Rule 10: the passage and its reference were drafted by an LLM and not reviewed yet. */
    val isAiGenerated: Boolean get() = source == "llm"
    val hasReference: Boolean get() = reference.isNotBlank()
}

/**
 * The sight-translation time limit (D-271): J→E 20 s plus one second per 2.5 Japanese characters, E→J 20 s plus
 * 1.2 s per English word, rounded up to 5 s, between 30 s and 300 s. `build_translation.py` computes the same.
 */
object SightTimer {
    fun seconds(text: String, direction: TranslationDirection): Int {
        val raw = if (direction == TranslationDirection.JE) {
            20 + text.count { !it.isWhitespace() } / 2.5
        } else {
            20 + text.split(Regex("\\s+")).count { w -> w.any { it.isLetterOrDigit() } } * 1.2
        }
        return (ceil(raw / 5.0).toInt() * 5).coerceIn(30, 300)
    }
}

/** One criterion of the four-part rubric, with what 0, 2 and 4 look like (the self-assessment screen, D-271). */
data class TranslationCriterion(val key: String, val label: String, val question: String, val levels: List<String>)

/** Used without a model: the learner compares with the reference and scores themselves on the same rubric. */
object TranslationRubric {
    val criteria: List<TranslationCriterion> = listOf(
        TranslationCriterion(
            "accuracy", "Accuracy", "Is the meaning right?",
            listOf("Wrong or missing meaning", "Several meaning errors", "One or two slips", "Minor nuance lost", "Same meaning as the reference"),
        ),
        TranslationCriterion(
            "completeness", "Completeness", "Is everything there? Check the key points.",
            listOf("Most points missing", "Half the points missing", "A point missing", "A detail missing", "Every point is there"),
        ),
        TranslationCriterion(
            "register", "Register", "Does the style fit the genre and the reader?",
            listOf("Wrong register throughout", "Mostly the wrong register", "Mixed registers", "One slip", "Right register throughout"),
        ),
        TranslationCriterion(
            "naturalness", "Naturalness", "Would a native speaker write it this way?",
            listOf("Hard to follow", "Clearly translated", "Understandable but stiff", "Mostly natural", "Reads like an original"),
        ),
    )

    /** 0–100 from four 0–4 scores. */
    fun percent(accuracy: Int, completeness: Int, register: Int, naturalness: Int): Int =
        ((accuracy + completeness + register + naturalness).coerceIn(0, 16) * 100 + 8) / 16
}

/** A passage the learner brings (pasted text, a reader document): no reference, graded against the source. */
object ImportedPassage {
    fun of(text: String, title: String = "", genre: String = "news", direction: TranslationDirection? = null): TranslationPassage {
        val clean = text.trim()
        val dir = direction ?: if (Kana.containsKanji(clean) || clean.any { Kana.isKana(it) }) TranslationDirection.JE else TranslationDirection.EJ
        return TranslationPassage(
            id = "user:${reviewKey(clean)}", direction = dir, genre = genre, level = "", ilr = "",
            title = title.ifBlank { clean.take(24) }, text = clean, reference = "", register = "", keyPoints = emptyList(),
            notes = "", origin = PassageOrigin.USER, originRef = null, sightSeconds = SightTimer.seconds(clean, dir), source = "user",
        )
    }
}
