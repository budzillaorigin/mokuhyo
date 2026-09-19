package app.tsumugi.pitch

import app.tsumugi.audio.AudioKeys
import app.tsumugi.audio.PitchTestItem
import app.tsumugi.jp.Mora
import app.tsumugi.jp.PitchAccent
import kotlin.random.Random

/**
 * What the learner picks (BRIEF_V2 §6.7): the pattern (平板/頭高/中高/尾高), the mora after which the pitch falls, or
 * which word of a same-kana group was spoken (the pitch set's own minimal pairs, 箸/橋/端).
 */
enum class PitchQuestionMode { PATTERN, DOWNSTEP, WORD_PAIR }

/** The four Tokyo accent patterns as the test names them; [code] is the `pattern` string of the pitch pack's items. */
enum class AccentPattern(val code: String, val ja: String, val en: String) {
    HEIBAN("heiban", "平板", "flat"),
    ATAMADAKA("atamadaka", "頭高", "head-high"),
    NAKADAKA("nakadaka", "中高", "middle-high"),
    ODAKA("odaka", "尾高", "tail-high"),
    ;

    companion object {
        fun of(code: String): AccentPattern? = entries.firstOrNull { it.code == code || it.name == code }

        /** The pattern of downstep [downstep] in a word of [moraCount] morae (0 = 平板). */
        fun of(downstep: Int, moraCount: Int): AccentPattern = when {
            downstep == 0 -> HEIBAN
            downstep == 1 -> ATAMADAKA
            downstep >= moraCount -> ODAKA
            else -> NAKADAKA
        }

        /** Patterns a word of [moraCount] morae can have (中高 needs three morae). */
        fun possible(moraCount: Int): List<AccentPattern> = if (moraCount >= 3) entries else listOf(HEIBAN, ATAMADAKA, ODAKA)
    }
}

/** One answer button. [id] is what [PitchTestSession.answer] takes. */
data class PitchOption(val id: String, val label: String, val detail: String = "")

/**
 * One question: play [clipKey] (the pitch pack, D-092/D-094; the clip says the word plus が), show [options].
 * [level] is the adaptive level the question was drawn at.
 */
data class PitchQuestion(val item: PitchTestItem, val mode: PitchQuestionMode, val options: List<PitchOption>, val level: Int) {
    val clipKey: String get() = AudioKeys.pitch(item.id)

    /** The option id that is right. */
    val expected: String get() = when (mode) {
        PitchQuestionMode.PATTERN -> patternOf(item).name
        PitchQuestionMode.DOWNSTEP -> item.downstep.toString()
        PitchQuestionMode.WORD_PAIR -> item.id
    }
}

/**
 * Feedback after an answer. [marks] draws the accent with ↑/↓ over the morae and the particle (は↑し↓が), so the
 * learner sees what was played; [chosenMarks] draws what they picked (null for word pairs).
 */
data class PitchFeedback(
    val question: PitchQuestion,
    val answer: String,
    val correct: Boolean,
    val marks: String,
    val chosenMarks: String?,
    /** The level after this answer (up after a streak, down after a miss). */
    val nextLevel: Int,
)

/**
 * Adaptive difficulty: five levels over mora length and question kind, moved by a 3-up/1-down staircase (three right in
 * a row → up, a miss → down), which settles where the learner gets about four in five right.
 *
 * | level | question | morae | options |
 * |---|---|---|---|
 * | 1 | pattern | 2 | 平板 / 頭高 / 尾高 |
 * | 2 | pattern | 2–3 | the possible patterns |
 * | 3 | pattern, homophones first | 2–4 | the possible patterns |
 * | 4 | downstep mora | 2–3 | 0 … n |
 * | 5 | downstep mora or which word | 2–4 | 0 … n, or the group's words |
 */
object PitchDifficulty {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 5
    const val STREAK_UP = 3

    fun moraRange(level: Int): IntRange = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
        1 -> 2..2
        2 -> 2..3
        4 -> 2..3
        else -> 2..4
    }

    fun modes(level: Int): List<PitchQuestionMode> = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
        1, 2, 3 -> listOf(PitchQuestionMode.PATTERN)
        4 -> listOf(PitchQuestionMode.DOWNSTEP)
        else -> listOf(PitchQuestionMode.DOWNSTEP, PitchQuestionMode.WORD_PAIR)
    }

    /** The level after an answer at [level] with [streak] right answers in a row (this one included). */
    fun next(level: Int, correct: Boolean, streak: Int): Int = when {
        !correct -> (level - 1).coerceAtLeast(MIN_LEVEL)
        streak >= STREAK_UP -> (level + 1).coerceAtMost(MAX_LEVEL)
        else -> level
    }
}

internal fun patternOf(item: PitchTestItem): AccentPattern =
    AccentPattern.of(item.pattern) ?: AccentPattern.of(item.downstep, item.moraCount)

/** "は↑し↓が": the word's morae with ↑ at the rise and ↓ at the fall, the carrier particle last. */
fun accentMarks(reading: String, downstep: Int, particle: String = "が"): String {
    val morae = Mora.split(reading) + particle
    val heights = PitchAccent(downstep, morae.size - 1).heights()
    return buildString {
        morae.forEachIndexed { i, m ->
            if (i > 0 && heights[i] != heights[i - 1]) append(if (heights[i]) '↑' else '↓')
            append(m)
        }
    }
}

/**
 * Builds questions from the pitch pack's items (only items whose clip is installed, `AudioPackRepository.pitchItems()`).
 * Items are weighted towards the learner's weak patterns and lengths, and the last [RECENT] items aren't repeated.
 */
class PitchQuestionPicker(items: List<PitchTestItem>, private val random: Random = Random.Default) {
    private val items = items.filter { it.moraCount >= 2 && it.reading.isNotEmpty() }
    private val byId = this.items.associateBy { it.id }
    private val recent = ArrayDeque<String>()

    val size: Int get() = items.size

    /**
     * A question at [level], or null when no item fits (tiny pool). [weakness] maps a pattern to its current error
     * rate (0…1) and biases the draw towards it. [mode] fixes the question type (a single-type drill); by
     * default the level decides.
     */
    fun next(level: Int, weakness: Map<AccentPattern, Double> = emptyMap(), mode: PitchQuestionMode? = null): PitchQuestion? {
        val lvl = level.coerceIn(PitchDifficulty.MIN_LEVEL, PitchDifficulty.MAX_LEVEL)
        val modes = mode?.let { listOf(it) } ?: PitchDifficulty.modes(lvl)
        for (m in modes.shuffled(random)) {
            val range = PitchDifficulty.moraRange(lvl)
            fun eligible(r: IntRange): List<PitchTestItem> {
                val fit = items.filter { it.moraCount in r }
                    .let { p -> if (m == PitchQuestionMode.WORD_PAIR) p.filter { groupOf(it).size >= 2 } else p }
                // Skip recent items, but never run dry: a small cell only avoids repeating the last one.
                return fit.filter { it.id !in recent }.ifEmpty { fit.filter { it.id != recent.lastOrNull() } }
            }
            // A fixed-type drill widens to every length rather than run dry (few 2-mora homophone groups).
            var pool = eligible(range).ifEmpty { if (mode != null) eligible(2..Int.MAX_VALUE) else emptyList() }
            if (lvl == 3) pool.filter { it.confusableWith.isNotEmpty() }.takeIf { it.size >= MIN_HOMOPHONE_POOL }?.let { pool = it }
            if (pool.isEmpty()) continue
            val item = weighted(pool, weakness)
            remember(item.id)
            return PitchQuestion(item, m, options(item, m), lvl)
        }
        return null
    }

    /** A question on one given item (retry a missed word, or a drill on a word from elsewhere). */
    fun question(item: PitchTestItem, mode: PitchQuestionMode, level: Int = PitchDifficulty.MIN_LEVEL): PitchQuestion =
        PitchQuestion(item, if (mode == PitchQuestionMode.WORD_PAIR && groupOf(item).size < 2) PitchQuestionMode.PATTERN else mode, emptyList(), level)
            .let { it.copy(options = options(item, it.mode)) }

    fun item(id: String): PitchTestItem? = byId[id]

    private fun groupOf(item: PitchTestItem): List<PitchTestItem> =
        (listOf(item) + item.confusableWith.mapNotNull { byId[it] }).distinctBy { it.id }

    private fun options(item: PitchTestItem, mode: PitchQuestionMode): List<PitchOption> = when (mode) {
        PitchQuestionMode.PATTERN -> AccentPattern.possible(item.moraCount).map { PitchOption(it.name, it.ja, it.en) }
        PitchQuestionMode.DOWNSTEP -> (0..item.moraCount).map { d ->
            PitchOption(d.toString(), if (d == 0) "0" else d.toString(), accentMarks(item.reading, d))
        }
        PitchQuestionMode.WORD_PAIR -> groupOf(item).sortedBy { it.downstep }.map { PitchOption(it.id, it.text, it.gloss) }
    }

    private fun weighted(pool: List<PitchTestItem>, weakness: Map<AccentPattern, Double>): PitchTestItem {
        val weights = pool.map { 1.0 + 3.0 * (weakness[patternOf(it)] ?: 0.0) }
        var r = random.nextDouble() * weights.sum()
        for ((i, w) in weights.withIndex()) {
            r -= w
            if (r <= 0) return pool[i]
        }
        return pool.last()
    }

    private fun remember(id: String) {
        recent.addLast(id)
        while (recent.size > RECENT.coerceAtMost(items.size - 1).coerceAtLeast(0)) recent.removeFirst()
    }

    companion object {
        const val RECENT = 8
        private const val MIN_HOMOPHONE_POOL = 6
    }
}
