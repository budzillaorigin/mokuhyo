package app.tsumugi.pitch

import app.tsumugi.audio.PitchTestItem
import app.tsumugi.db.Pitch_test_result
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.speaking.PronunciationService
import app.tsumugi.speech.PitchVerdict
import app.tsumugi.speech.PronunciationReport
import app.tsumugi.speech.ShadowingReport
import app.tsumugi.speech.WordTarget
import app.tsumugi.study.MinimalPairService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Right answers out of attempts. */
data class PitchTally(val attempts: Int, val correct: Int) {
    val accuracy: Double? get() = if (attempts == 0) null else correct.toDouble() / attempts
}

/**
 * A pair of patterns the learner may confuse (平板/尾高 is the classic). [attempts]: answers whose expected pattern is in
 * the pair; [confusions]: those answered with the other one of the pair.
 */
data class PatternPairTally(val a: AccentPattern, val b: AccentPattern, val attempts: Int, val confusions: Int) {
    val confusionRate: Double? get() = if (attempts == 0) null else confusions.toDouble() / attempts
}

/** Per-pattern, per-length and per-confusable-pair results (BRIEF_V2 §6.7), from every synced answer. */
data class PitchStats(
    val total: PitchTally,
    val byPattern: Map<AccentPattern, PitchTally>,
    val byMoraCount: Map<Int, PitchTally>,
    val byMode: Map<PitchQuestionMode, PitchTally>,
    /** Every pattern pair seen, most confused first. */
    val pairs: List<PatternPairTally>,
    /** Where the next session starts: the latest answer's level, one lower after a miss; 1 without answers. */
    val level: Int,
) {
    /** Error rate per pattern, for weighting the next questions towards weak spots. */
    val weakness: Map<AccentPattern, Double> get() = byPattern.mapValues { (_, t) -> 1.0 - (t.accuracy ?: 1.0) }

    companion object {
        /**
         * Stats over answer rows. The answered pattern comes from the answer itself (PATTERN), from the downstep and the
         * item's length (DOWNSTEP), or from the answered item's pattern (WORD_PAIR, via [patternOfItem]).
         */
        fun of(rows: List<PitchAnswerRow>, patternOfItem: (String) -> AccentPattern? = { null }): PitchStats {
            fun tally(list: List<PitchAnswerRow>) = PitchTally(list.size, list.count { it.correct })
            val withPattern = rows.mapNotNull { r -> AccentPattern.of(r.pattern)?.let { it to r } }
            val pairCounts = HashMap<Pair<AccentPattern, AccentPattern>, IntArray>()
            for ((expected, r) in withPattern) {
                val answered = answeredPattern(r, patternOfItem)
                for (other in AccentPattern.possible(r.moraCount)) {
                    if (other == expected) continue
                    val key = if (expected.ordinal < other.ordinal) expected to other else other to expected
                    val c = pairCounts.getOrPut(key) { IntArray(2) }
                    c[0]++
                    if (answered == other) c[1]++
                }
            }
            return PitchStats(
                total = tally(rows),
                byPattern = withPattern.groupBy({ it.first }, { it.second }).mapValues { tally(it.value) },
                byMoraCount = rows.groupBy { it.moraCount }.mapValues { tally(it.value) }.entries.sortedBy { it.key }.associate { it.key to it.value },
                byMode = rows.mapNotNull { r -> runCatching { PitchQuestionMode.valueOf(r.mode) }.getOrNull()?.let { it to r } }
                    .groupBy({ it.first }, { it.second }).mapValues { tally(it.value) },
                pairs = pairCounts.map { (k, v) -> PatternPairTally(k.first, k.second, v[0], v[1]) }
                    .sortedWith(compareByDescending<PatternPairTally> { it.confusionRate ?: 0.0 }.thenByDescending { it.attempts }),
                level = startLevel(rows),
            )
        }

        /** Where the next session starts: the last answer's level, one lower if it was a miss (the staircase's step). */
        private fun startLevel(rows: List<PitchAnswerRow>): Int {
            val last = rows.maxWithOrNull(compareBy<PitchAnswerRow> { it.answeredAt }) ?: return PitchDifficulty.MIN_LEVEL
            return (last.level - if (last.correct) 0 else 1).coerceIn(PitchDifficulty.MIN_LEVEL, PitchDifficulty.MAX_LEVEL)
        }

        private fun answeredPattern(r: PitchAnswerRow, patternOfItem: (String) -> AccentPattern?): AccentPattern? =
            when (r.mode) {
                PitchQuestionMode.PATTERN.name -> AccentPattern.of(r.answer)
                PitchQuestionMode.DOWNSTEP.name -> r.answer.toIntOrNull()?.let { AccentPattern.of(it, r.moraCount) }
                else -> if (r.correct) AccentPattern.of(r.pattern) else patternOfItem(r.answer)
            }
    }
}

/** One stored answer (`pitch_test_result`). */
data class PitchAnswerRow(
    val itemId: String,
    val mode: String,
    val expected: String,
    val answer: String,
    val correct: Boolean,
    val pattern: String,
    val moraCount: Int,
    val level: Int,
    val responseMs: Long,
    val answeredAt: Long,
)

/**
 * The "perception → production" link (BRIEF_V2 §6.7): after hearing a word, the learner says it; the pronunciation
 * panel's analyzer judges the pitch against the item's known accent. [verdict] is the word's pitch verdict,
 * [shadowing] the comparison with the pack clip when the platform decoded it.
 */
data class PitchProductionResult(
    val item: PitchTestItem,
    val expectedMarks: String,
    val verdict: PitchVerdict,
    val observedMarks: String,
    val report: PronunciationReport,
    val shadowing: ShadowingReport?,
) {
    val matched: Boolean get() = verdict == PitchVerdict.MATCH
}

/** The drill types of the module; minimal pairs (the practice pack's, on SRS) are one of them. */
enum class PitchDrill { PATTERN_TEST, DOWNSTEP_TEST, WORD_PAIRS, MINIMAL_PAIRS }

/**
 * A test run: [next] draws a question at the current adaptive level, [answer] checks it, stores the answer and moves
 * the level. The level starts where the learner's last answer left it.
 */
class PitchTestSession internal constructor(
    private val service: PitchTestService,
    private val picker: PitchQuestionPicker,
    startLevel: Int,
    private val weakness: Map<AccentPattern, Double>,
    /** Fixed mode for a single-type drill (null = the level decides). */
    private val drill: PitchQuestionMode?,
) {
    var level: Int = startLevel.coerceIn(PitchDifficulty.MIN_LEVEL, PitchDifficulty.MAX_LEVEL)
        private set
    var streak: Int = 0
        private set
    var answered: Int = 0
        private set
    var correct: Int = 0
        private set
    var current: PitchQuestion? = null
        private set

    /** The next question, or null when the pool is empty. */
    fun next(): PitchQuestion? {
        // Single-type drills keep the level's lengths but always ask [drill].
        val q = picker.next(level, weakness, drill)
        current = q
        return q
    }

    /** Checks [optionId] for the current question and records it. */
    @Throws(Exception::class)
    suspend fun answer(optionId: String, responseMs: Long): PitchFeedback {
        val q = current ?: error("no question")
        val ok = optionId == q.expected
        streak = if (ok) streak + 1 else 0
        answered++
        if (ok) correct++
        val next = PitchDifficulty.next(level, ok, streak)
        if (next != level) streak = 0
        service.record(q, optionId, ok, responseMs)
        level = next
        current = null
        val chosen = when (q.mode) {
            PitchQuestionMode.PATTERN -> AccentPattern.of(optionId)?.let { downstepFor(it, q.item.moraCount) }
            PitchQuestionMode.DOWNSTEP -> optionId.toIntOrNull()
            PitchQuestionMode.WORD_PAIR -> null
        }
        return PitchFeedback(
            q, optionId, ok, accentMarks(q.item.reading, q.item.downstep),
            chosen?.let { accentMarks(q.item.reading, it) }, level,
        )
    }

    private fun downstepFor(p: AccentPattern, morae: Int): Int = when (p) {
        AccentPattern.HEIBAN -> 0
        AccentPattern.ATAMADAKA -> 1
        AccentPattern.NAKADAKA -> (morae - 1).coerceAtLeast(2)
        AccentPattern.ODAKA -> morae
    }
}

/**
 * The pitch-accent perception test (BRIEF_V2 §6.7; DECISIONS D-284, D-285). Uses only the pitch audio pack's items,
 * whose accents were set explicitly at render time (D-093/D-094); the app hides the module when [available] is false
 * (no pack, rule 20: system TTS can't guarantee an accent). Answers go to `pitch_test_result` (union sync).
 */
class PitchTestService(
    private val db: TsumugiDatabase,
    private val deviceId: String,
    /** `AudioPackRepository.pitchItems()`: only items whose clip is installed. */
    val items: List<PitchTestItem>,
    private val pronunciation: PronunciationService? = null,
    private val minimalPairs: suspend () -> MinimalPairService? = { null },
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.pitchTestQueries
    private val byId = items.associateBy { it.id }

    /** False without the pitch pack: the screen stays hidden. */
    val available: Boolean get() = items.isNotEmpty()

    /** Drill types that can run now: the three test types with the pack, minimal pairs with the practice pack. */
    @Throws(Exception::class)
    suspend fun drills(): List<PitchDrill> = buildList {
        if (available) {
            add(PitchDrill.PATTERN_TEST)
            add(PitchDrill.DOWNSTEP_TEST)
            if (items.any { it.confusableWith.isNotEmpty() }) add(PitchDrill.WORD_PAIRS)
        }
        if (minimalPairs() != null) add(PitchDrill.MINIMAL_PAIRS)
    }

    /**
     * A test session. [drill] null = the adaptive mix; PATTERN_TEST / DOWNSTEP_TEST / WORD_PAIRS fix the question type.
     * MINIMAL_PAIRS isn't a session here: start it with [minimalPairDrill].
     */
    @Throws(Exception::class)
    suspend fun start(drill: PitchDrill? = null, random: Random = Random.Default): PitchTestSession {
        val stats = stats()
        val mode = when (drill) {
            PitchDrill.PATTERN_TEST -> PitchQuestionMode.PATTERN
            PitchDrill.DOWNSTEP_TEST -> PitchQuestionMode.DOWNSTEP
            PitchDrill.WORD_PAIRS -> PitchQuestionMode.WORD_PAIR
            PitchDrill.MINIMAL_PAIRS, null -> null
        }
        return PitchTestSession(this, PitchQuestionPicker(items, random), stats.level, stats.weakness, mode)
    }

    /** The existing minimal-pair drill (practice pack pairs on SRS), as this module's fourth drill type. */
    @Throws(Exception::class)
    suspend fun minimalPairDrill(): MinimalPairService? = minimalPairs()

    @Throws(Exception::class)
    suspend fun stats(sinceMs: Long = 0): PitchStats = withContext(Dispatchers.IO) {
        val rows = q.pitchResultsSince(sinceMs).executeAsList().map { it.toRow() }
        PitchStats.of(rows) { id -> byId[id]?.let(::patternOf) }
    }

    /** The latest answers, newest first (a history list). */
    @Throws(Exception::class)
    suspend fun recent(limit: Int = 50): List<PitchAnswerRow> = withContext(Dispatchers.IO) {
        q.recentPitchResults(limit.toLong()).executeAsList().map { it.toRow() }
    }

    fun item(id: String): PitchTestItem? = byId[id]

    /**
     * Perception → production: scores the learner saying [item] (with the carrier が, like the clip) against its known
     * accent. [pcm16k] is 16 kHz mono; [transcript] what the recognizer heard, if any; [referencePcm16k] the decoded
     * pack clip for a shadowing comparison, if the platform has it. Null without the pronunciation service.
     */
    @Throws(Exception::class)
    suspend fun production(item: PitchTestItem, pcm16k: FloatArray, transcript: String? = null, referencePcm16k: FloatArray? = null): PitchProductionResult? {
        val service = pronunciation ?: return null
        val targets = productionTargets(item)
        val report = service.analyzeTargets(targets, transcript, pcm16k)
        val word = report.words.firstOrNull()
        val shadowing = referencePcm16k?.let { service.shadowing(it, pcm16k) }
        return PitchProductionResult(
            item, accentMarks(item.reading, item.downstep), word?.verdict ?: PitchVerdict.UNCLEAR, word?.observedMarks.orEmpty(), report, shadowing,
        )
    }

    internal suspend fun record(question: PitchQuestion, answer: String, correct: Boolean, responseMs: Long) = withContext(Dispatchers.IO) {
        val item = question.item
        q.insertPitchResult(
            Uuid.random().toString(), item.id, question.mode.name, question.expected, answer, if (correct) 1 else 0,
            patternOf(item).name, item.moraCount.toLong(), question.level.toLong(), responseMs.coerceAtLeast(0), clock.now().toEpochMilliseconds(), deviceId,
        )
    }

    private fun Pitch_test_result.toRow() = PitchAnswerRow(
        item_id, mode, expected, answer, correct != 0L, pattern, mora_count.toInt(), level.toInt(), response_ms, answered_at,
    )

    companion object {
        /** The word with its accent and the carrier particle as its own (unjudged) word, as the clip says it. */
        fun productionTargets(item: PitchTestItem): List<WordTarget> =
            listOf(WordTarget(item.reading, item.downstep, followedByParticle = true), WordTarget(carrier(item), null))

        private fun carrier(item: PitchTestItem): String = item.spoken.removePrefix(item.reading).ifEmpty { "が" }
    }
}
