package app.tsumugi.media

import app.tsumugi.jp.Kana
import app.tsumugi.platform.normalizeNfc
import kotlin.random.Random

/** Playback speeds for the media player (BRIEF_V2 G-04): one range for both apps. */
object MediaPlayback {
    const val SPEED_MIN = 0.7
    const val SPEED_MAX = 1.2
    const val SPEED_DEFAULT = 1.0

    /** The steps the speed control offers, slowest first. */
    val SPEEDS: List<Double> = listOf(0.7, 0.8, 0.9, 1.0, 1.1, 1.2)

    fun clampSpeed(speed: Double): Double = if (speed.isNaN()) SPEED_DEFAULT else speed.coerceIn(SPEED_MIN, SPEED_MAX)

    /** The next step up or down from [speed] (clamped at the ends). */
    fun step(speed: Double, up: Boolean): Double {
        val s = clampSpeed(speed)
        return if (up) SPEEDS.firstOrNull { it > s + 1e-9 } ?: SPEED_MAX else SPEEDS.lastOrNull { it < s - 1e-9 } ?: SPEED_MIN
    }
}

enum class QuizMode {
    /** Type what you heard. */
    TYPE,
    /** Pick the line you heard among [SubtitleQuiz.CHOICES] (distractors are other lines of the same media). */
    PICK,
}

enum class QuizPhase { HIDDEN, ANSWERED, REVEALED }

/** One hidden cue: play [cue] with the subtitle hidden, then take an answer, then reveal. */
data class QuizQuestion(
    val index: Int,
    val cue: Cue,
    /** For [QuizMode.PICK]: the choices (one is [Cue.text]); empty for TYPE. */
    val choices: List<String>,
)

/** How an answer compared with the line: [score] 0..1 (kana-folded character similarity), [correct] ≥ [SubtitleQuiz.PASS]. */
data class QuizResult(val correct: Boolean, val score: Double, val expected: String, val given: String)

/**
 * The hide-subtitle quiz (BRIEF_V2 G-04): the player hides a cue, the learner types or picks what was said, and
 * the line is revealed. State per question is [QuizPhase]: HIDDEN → (answer) ANSWERED → (reveal) REVEALED; the
 * learner may also reveal without answering. Typed answers are compared after NFC, katakana→hiragana folding and
 * dropping spaces/punctuation (search-style folding, rule 7), so ひらがな for a kanji line still scores partly.
 */
class SubtitleQuiz(
    private val cues: List<Cue>,
    val mode: QuizMode,
    seed: Long = 0,
    /** Cues shorter than this (in characters after folding) aren't asked: "はい" isn't a listening test. */
    minChars: Int = 4,
) {
    private val random = Random(seed)
    private val eligible = cues.indices.filter { fold(cues[it].text).length >= minChars }
    private val phases = HashMap<Int, QuizPhase>()
    private val results = HashMap<Int, QuizResult>()

    /** Cue indices that can be asked, in order. */
    val questionIndices: List<Int> get() = eligible

    fun question(index: Int): QuizQuestion {
        val cue = cues[index]
        val choices = if (mode == QuizMode.PICK) {
            val others = cues.indices.filter { it != index && fold(cues[it].text) != fold(cue.text) }.shuffled(random).take(CHOICES - 1)
            (others.map { cues[it].text } + cue.text).shuffled(random)
        } else {
            emptyList()
        }
        phases.getOrPut(index) { QuizPhase.HIDDEN }
        return QuizQuestion(index, cue, choices)
    }

    fun phase(index: Int): QuizPhase = phases[index] ?: QuizPhase.HIDDEN

    /** Grades an answer (typed text or the picked choice) and moves the question to ANSWERED. */
    fun answer(index: Int, given: String): QuizResult {
        val expected = cues[index].text
        val score = if (mode == QuizMode.PICK) (if (normalizeNfc(given.trim()) == expected) 1.0 else 0.0) else similarity(expected, given)
        return QuizResult(score >= PASS, score, expected, given).also {
            results[index] = it
            if (phase(index) == QuizPhase.HIDDEN) phases[index] = QuizPhase.ANSWERED
        }
    }

    /** Shows the line (with or without an answer first). */
    fun reveal(index: Int): Cue {
        phases[index] = QuizPhase.REVEALED
        return cues[index]
    }

    fun result(index: Int): QuizResult? = results[index]

    /** Answered questions and how many passed. */
    val score: Pair<Int, Int> get() = results.values.count { it.correct } to results.size

    companion object {
        const val CHOICES = 4
        const val PASS = 0.8

        fun fold(s: String): String = Kana.toHiragana(normalizeNfc(s)).filter { it.isLetterOrDigit() }

        /** 1 − (edit distance / longer length) over folded text. */
        fun similarity(expected: String, given: String): Double {
            val a = fold(expected)
            val b = fold(given)
            if (a.isEmpty() && b.isEmpty()) return 1.0
            val longer = maxOf(a.length, b.length)
            return 1.0 - levenshtein(a, b).toDouble() / longer
        }

        private fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}
