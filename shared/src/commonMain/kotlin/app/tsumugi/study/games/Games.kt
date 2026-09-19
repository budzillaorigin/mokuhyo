package app.tsumugi.study.games

import app.tsumugi.jp.Kana
import app.tsumugi.jp.Mora
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The mini-games (BRIEF_V2 §6.9; DECISIONS D-286, D-287), written from the one-line concepts in the brief (timed
 * true/false matching; kana assembly under time). Ulangi, which inspired them, is GPL-3.0, so none of its code was read
 * (docs/LICENSES.md "Inspiration, no content used"). Pure game logic; the UI draws it and reports taps.
 */
enum class GameKind { REFLEX, ATOM }

/** A word the games can use: the learner's own studied words first, frequency-list words otherwise. */
data class GameWord(val id: String, val text: String, val reading: String, val meaning: String)

/** A finished round, as stored in `game_score` (weekly challenge points). */
data class GameResult(val game: GameKind, val score: Int, val correct: Int, val total: Int, val bestStreak: Int, val durationMs: Long) {
    val accuracy: Double? get() = if (total == 0) null else correct.toDouble() / total
}

// --- Reflex ------------------------------------------------------------------------------------------------------

/** One Reflex card: does [shownMeaning] belong to [word]? Answer before [deadline]. */
data class ReflexCard(val word: GameWord, val shownMeaning: String, val isMatch: Boolean, val deadline: Instant, val timeLimit: Duration)

/** The outcome of one Reflex answer (or a timeout). [points] were added to [score]. */
data class ReflexOutcome(val card: ReflexCard, val correct: Boolean, val timedOut: Boolean, val points: Int, val streak: Int, val score: Int)

/**
 * Reflex: timed true/false matching of word ↔ meaning with streak scoring. A right answer scores 10 × the streak
 * multiplier (×1, then +1 every [STREAK_STEP] in a row, at most ×[MAX_MULTIPLIER]) plus a speed bonus of up to 5 for
 * answering in the first half of the card's time; a wrong answer or a timeout scores 0 and resets the streak. Each card's
 * time shrinks as the streak grows (4 s down to 1.5 s). The round lasts [roundLength].
 */
class ReflexGame(
    words: List<GameWord>,
    private val random: Random = Random.Default,
    private val clock: Clock = Clock.System,
    val roundLength: Duration = DEFAULT_ROUND,
) {
    private val words = words.filter { it.meaning.isNotBlank() }.distinctBy { it.id }
    val startedAt: Instant = clock.now()
    var score = 0
        private set
    var streak = 0
        private set
    var bestStreak = 0
        private set
    var answered = 0
        private set
    var correct = 0
        private set
    var current: ReflexCard? = null
        private set
    private var shownAt: Instant = startedAt
    private var last: GameWord? = null

    val remaining: Duration get() = (roundLength - (clock.now() - startedAt)).coerceAtLeast(Duration.ZERO)
    val finished: Boolean get() = remaining == Duration.ZERO || words.size < MIN_WORDS

    /** The next card, or null when the round is over (or there are fewer than [MIN_WORDS] words). */
    fun next(): ReflexCard? {
        if (finished) return null
        val word = words.filter { it != last }.random(random)
        last = word
        val match = random.nextBoolean()
        val meaning = if (match) word.meaning else decoy(word)
        val limit = timeLimit(streak)
        shownAt = clock.now()
        return ReflexCard(word, meaning, meaning == word.meaning, shownAt + limit, limit).also { current = it }
    }

    /** The learner said "match" ([saysMatch] true) or "no match". Late answers count as timeouts. */
    fun answer(saysMatch: Boolean): ReflexOutcome? {
        val card = current ?: return null
        val now = clock.now()
        if (now > card.deadline) return timeout()
        return settle(card, saysMatch == card.isMatch, timedOut = false, elapsed = now - shownAt)
    }

    /** The card's time ran out without an answer. */
    fun timeout(): ReflexOutcome? {
        val card = current ?: return null
        return settle(card, false, timedOut = true, elapsed = card.timeLimit)
    }

    fun result(): GameResult = GameResult(
        GameKind.REFLEX, score, correct, answered, bestStreak, (clock.now() - startedAt).coerceAtMost(roundLength).inWholeMilliseconds,
    )

    private fun settle(card: ReflexCard, ok: Boolean, timedOut: Boolean, elapsed: Duration): ReflexOutcome {
        current = null
        answered++
        val points = if (ok) {
            correct++
            streak++
            bestStreak = maxOf(bestStreak, streak)
            points(streak, elapsed, card.timeLimit)
        } else {
            streak = 0
            0
        }
        score += points
        return ReflexOutcome(card, ok, timedOut, points, streak, score)
    }

    /** Another word's meaning that isn't also a meaning of [word] (so "no match" is really right). */
    private fun decoy(word: GameWord): String {
        val own = word.meaning.split(';', ',').map { it.trim().lowercase() }.toSet()
        val others = words.filter { w -> w.id != word.id && w.meaning.split(';', ',').none { it.trim().lowercase() in own } }
        return (others.ifEmpty { words.filter { it.id != word.id } }).random(random).meaning
    }

    companion object {
        val DEFAULT_ROUND: Duration = 60.seconds
        const val MIN_WORDS = 4
        const val BASE_POINTS = 10
        const val STREAK_STEP = 5
        const val MAX_MULTIPLIER = 5
        const val MAX_SPEED_BONUS = 5
        private val SLOWEST = 4000.milliseconds
        private val FASTEST = 1500.milliseconds
        private val SHRINK_PER_STREAK = 150.milliseconds

        /** Time allowed for the next card at [streak] right answers in a row. */
        fun timeLimit(streak: Int): Duration = (SLOWEST - SHRINK_PER_STREAK * streak).coerceAtLeast(FASTEST)

        /** Points for a right answer that made the streak [streak], answered after [elapsed] of [limit]. */
        fun points(streak: Int, elapsed: Duration, limit: Duration): Int {
            val multiplier = (1 + (streak - 1) / STREAK_STEP).coerceIn(1, MAX_MULTIPLIER)
            val half = limit / 2
            val bonus = if (elapsed >= half) 0 else ((half - elapsed) / half * MAX_SPEED_BONUS).toInt().coerceIn(0, MAX_SPEED_BONUS)
            return BASE_POINTS * multiplier + bonus
        }
    }
}

// --- Atom --------------------------------------------------------------------------------------------------------

/** A kana tile. [id] is unique within the puzzle; several tiles can show the same kana. */
data class AtomTile(val id: Int, val kana: String)

/** One Atom puzzle: build [word]'s reading from [tiles] (its morae plus decoys) before [deadline]. */
data class AtomPuzzle(val word: GameWord, val target: List<String>, val tiles: List<AtomTile>, val deadline: Instant, val timeLimit: Duration) {
    /** The prompt: the meaning, plus the written form when it has kanji (reading it is the task). */
    val prompt: String get() = if (Kana.containsKanji(word.text)) "${word.text} — ${word.meaning}" else word.meaning
}

enum class AtomTapResult { PLACED, WRONG, SOLVED, TIMED_OUT }

/** After a tap: [assembled] so far, the puzzle's points once solved, the round's [score]. */
data class AtomState(val result: AtomTapResult, val assembled: List<String>, val points: Int, val mistakes: Int, val score: Int)

/**
 * Atom: spelling/kana assembly under time. The learner taps the reading's morae in order from shuffled tiles that
 * include look-alike decoys (voicing, small kana, long vowels). A solved puzzle scores 5 per mora plus a time bonus
 * (up to 10) minus 3 per wrong tap (never below half the mora points); the streak counts puzzles
 * solved without a mistake. Each puzzle gets 3 s + 1.5 s per mora; the round lasts [roundLength].
 */
class AtomGame(
    words: List<GameWord>,
    private val random: Random = Random.Default,
    private val clock: Clock = Clock.System,
    val roundLength: Duration = DEFAULT_ROUND,
) {
    private val words = words.filter { w -> Kana.isAllKana(w.reading) && Mora.count(w.reading) in MIN_MORAE..MAX_MORAE }.distinctBy { it.id }
    val startedAt: Instant = clock.now()
    var score = 0
        private set
    var streak = 0
        private set
    var bestStreak = 0
        private set
    var attempted = 0
        private set
    var solved = 0
        private set
    var current: AtomPuzzle? = null
        private set
    private val assembled = ArrayList<String>()
    private val placed = ArrayList<Int>()
    private var mistakes = 0
    private var shownAt = startedAt
    private var last: GameWord? = null

    val remaining: Duration get() = (roundLength - (clock.now() - startedAt)).coerceAtLeast(Duration.ZERO)
    val finished: Boolean get() = remaining == Duration.ZERO || words.isEmpty()

    /** The next puzzle (an unsolved one counts as attempted), or null when the round is over. */
    fun next(): AtomPuzzle? {
        if (current != null) giveUp()
        if (finished) return null
        val word = words.filter { it != last || words.size == 1 }.random(random)
        last = word
        val target = Mora.split(word.reading)
        val tiles = (target + decoys(target)).shuffled(random).mapIndexed { i, k -> AtomTile(i, k) }
        val limit = puzzleTime(target.size)
        assembled.clear()
        placed.clear()
        mistakes = 0
        shownAt = clock.now()
        attempted++
        return AtomPuzzle(word, target, tiles, shownAt + limit, limit).also { current = it }
    }

    /** Tapped [tileId]: right if it is the next mora (any tile showing that kana counts). */
    fun tap(tileId: Int): AtomState? {
        val p = current ?: return null
        val now = clock.now()
        if (now > p.deadline) return giveUp(AtomTapResult.TIMED_OUT)
        val tile = p.tiles.firstOrNull { it.id == tileId } ?: return state(AtomTapResult.WRONG, 0)
        val expected = p.target[assembled.size]
        if (tileId in placed || !sameKana(tile.kana, expected)) {
            mistakes++
            streak = 0
            return state(AtomTapResult.WRONG, 0)
        }
        placed += tileId
        assembled += tile.kana
        if (assembled.size < p.target.size) return state(AtomTapResult.PLACED, 0)
        current = null
        solved++
        if (mistakes == 0) {
            streak++
            bestStreak = maxOf(bestStreak, streak)
        }
        val pts = points(p.target.size, mistakes, now - shownAt, p.timeLimit)
        score += pts
        return state(AtomTapResult.SOLVED, pts)
    }

    /** Removes the last placed tile (an undo button). */
    fun undo(): AtomState? {
        current ?: return null
        if (assembled.isNotEmpty()) {
            assembled.removeAt(assembled.lastIndex)
            placed.removeAt(placed.lastIndex)
        }
        return state(AtomTapResult.PLACED, 0)
    }

    /** The puzzle's time ran out, or the learner skipped: no points, streak reset. */
    fun giveUp(result: AtomTapResult = AtomTapResult.TIMED_OUT): AtomState? {
        current ?: return null
        current = null
        streak = 0
        return state(result, 0)
    }

    fun result(): GameResult = GameResult(
        GameKind.ATOM, score, solved, attempted, bestStreak, (clock.now() - startedAt).coerceAtMost(roundLength).inWholeMilliseconds,
    )

    private fun state(r: AtomTapResult, pts: Int) = AtomState(r, assembled.toList(), pts, mistakes, score)

    private fun decoys(target: List<String>): List<String> {
        val want = (target.size / 2).coerceIn(2, MAX_DECOYS)
        val out = ArrayList<String>()
        for (m in target.shuffled(random)) {
            if (out.size >= want) break
            lookAlike(m)?.takeIf { d -> target.none { sameKana(it, d) } && out.none { sameKana(it, d) } }?.let { out += it }
        }
        val fill = words.flatMap { Mora.split(it.reading) }.distinct().shuffled(random)
        for (m in fill) {
            if (out.size >= want) break
            if (target.none { sameKana(it, m) } && out.none { sameKana(it, m) }) out += m
        }
        return out
    }

    companion object {
        val DEFAULT_ROUND: Duration = 90.seconds
        const val MIN_MORAE = 2
        const val MAX_MORAE = 8
        const val MAX_DECOYS = 4
        const val POINTS_PER_MORA = 5
        const val MAX_TIME_BONUS = 10
        const val MISTAKE_PENALTY = 3

        /** Time for a puzzle of [morae] morae. */
        fun puzzleTime(morae: Int): Duration = 3.seconds + 1500.milliseconds * morae

        fun points(morae: Int, mistakes: Int, elapsed: Duration, limit: Duration): Int {
            val base = POINTS_PER_MORA * morae
            val bonus = if (elapsed >= limit) 0 else ((limit - elapsed) / limit * MAX_TIME_BONUS).toInt().coerceIn(0, MAX_TIME_BONUS)
            return (base + bonus - MISTAKE_PENALTY * mistakes).coerceAtLeast(base / 2)
        }

        /** Hiragana and katakana count as the same tile (a katakana word can be built from either script). */
        internal fun sameKana(a: String, b: String) = Kana.toHiragana(a) == Kana.toHiragana(b)

        /** A mora a learner confuses with [m]: voicing flipped, small/large kana, or a long vowel dropped. */
        internal fun lookAlike(m: String): String? {
            val h = Kana.toHiragana(m)
            val swap = VOICING[h] ?: VOICING.entries.firstOrNull { it.value == h }?.key ?: SIZE[h] ?: SIZE.entries.firstOrNull { it.value == h }?.key
            ?: return null
            return if (m != h) Kana.toKatakana(swap) else swap
        }

        private val VOICING = mapOf(
            "か" to "が", "き" to "ぎ", "く" to "ぐ", "け" to "げ", "こ" to "ご", "さ" to "ざ", "し" to "じ", "す" to "ず",
            "せ" to "ぜ", "そ" to "ぞ", "た" to "だ", "ち" to "ぢ", "て" to "で", "と" to "ど", "は" to "ば", "ひ" to "び",
            "ふ" to "ぶ", "へ" to "べ", "ほ" to "ぼ",
        )
        private val SIZE = mapOf(
            "っ" to "つ", "ゃ" to "や", "ゅ" to "ゆ", "ょ" to "よ", "きゃ" to "きや", "しゅ" to "しゆ", "ちょ" to "ちよ",
            "ー" to "う", "ん" to "む",
        )
    }
}

/** Builds a game pool: the learner's words first, topped up from the frequency list, most useful first. */
object GameWords {
    const val DEFAULT_POOL = 200

    /** Merges [studied] (the learner's items) and [common] (frequency-list words), dropping duplicates by text. */
    fun pool(studied: List<GameWord>, common: List<GameWord>, size: Int = DEFAULT_POOL): List<GameWord> =
        (studied + common).filter { it.meaning.isNotBlank() && it.reading.isNotBlank() }.distinctBy { it.text }.take(size)
}
