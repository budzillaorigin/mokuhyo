package app.tsumugi.srs

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class FsrsTest {

    private val t0 = Instant.parse("2022-11-29T12:30:00Z")

    /** The configuration py-fsrs's own tests use (its defaults: learning steps 1 min, 10 min). */
    private fun referenceScheduler(fuzz: Boolean = false, learning: List<Duration> = listOf(1.minutes, 10.minutes)) =
        FsrsScheduler(FsrsParameters(learningSteps = learning, relearningSteps = listOf(10.minutes), enableFuzzing = fuzz))

    private fun assertNear(expected: Double, actual: Double?, tolerance: Double = 1e-4) {
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "expected $expected, got $actual")
    }

    // --- Reference vectors (py-fsrs tests/test_basic.py) -----------------------------------------------

    @Test
    fun referenceIntervalSequence() {
        // test_review_card: Good×6, Again×2, Good×5, each review on the due date.
        val ratings = List(6) { Rating.GOOD } + List(2) { Rating.AGAIN } + List(5) { Rating.GOOD }
        val scheduler = referenceScheduler()
        var card = FsrsCard(due = t0)
        var now = t0
        val intervals = ratings.map { rating ->
            card = scheduler.review(card, rating, now)
            now = card.due
            wholeDaysBetween(card.lastReview!!, card.due)
        }
        assertEquals(listOf<Long>(0, 2, 11, 46, 163, 498, 0, 0, 2, 4, 7, 12, 21), intervals)
    }

    @Test
    fun referenceMemoState() {
        // test_memo_state: Again then Good×5 at cumulative day offsets 0,0,1,3,8,21.
        val scheduler = referenceScheduler(fuzz = true)
        val ratings = listOf(Rating.AGAIN) + List(5) { Rating.GOOD }
        val offsets = listOf(0, 0, 1, 3, 8, 21)
        var card = FsrsCard(due = t0)
        var now = t0
        for ((rating, days) in ratings.zip(offsets)) {
            now += days.days
            card = scheduler.review(card, rating, now)
        }
        assertNear(53.62691, card.stability)
        assertNear(6.3574867, card.difficulty)
    }

    @Test
    fun referenceRepeatedEasyBottomsOutDifficulty() {
        val scheduler = referenceScheduler()
        var card = FsrsCard(due = t0)
        repeat(10) { card = scheduler.review(card, Rating.EASY, t0 + it.microseconds) }
        assertEquals(1.0, card.difficulty)
    }

    @Test
    fun referenceStabilityLowerBound() {
        val scheduler = FsrsScheduler()
        var card = FsrsCard(due = t0)
        repeat(1000) {
            card = scheduler.review(card, Rating.AGAIN, card.due + 1.days)
            assertTrue(card.stability!! >= Fsrs.STABILITY_MIN)
        }
    }

    @Test
    fun referenceSameDayHardDoesNotDecreaseStability() {
        val scheduler = referenceScheduler(learning = emptyList())
        val first = scheduler.review(FsrsCard(due = t0), Rating.GOOD, t0)
        val second = scheduler.review(first, Rating.HARD, t0 + 1.minutes)
        assertEquals(first.stability, second.stability)
    }

    @Test
    fun referenceLongTermStabilityFromRelearning() {
        val scheduler = referenceScheduler(fuzz = true)
        var card = scheduler.review(FsrsCard(due = t0), Rating.EASY, t0)
        assertEquals(CardState.REVIEW, card.state)
        card = scheduler.review(card, Rating.AGAIN, card.due)
        assertEquals(CardState.RELEARNING, card.state)
        card = scheduler.review(card, Rating.GOOD, card.due + 1.days)
        assertEquals(CardState.REVIEW, card.state)
    }

    @Test
    fun referenceMaximumInterval() {
        val scheduler = FsrsScheduler(FsrsParameters(maximumIntervalDays = 100))
        var card = FsrsCard(due = t0)
        for (r in listOf(Rating.EASY, Rating.GOOD, Rating.EASY, Rating.GOOD)) {
            card = scheduler.review(card, r, card.due)
            assertTrue(wholeDaysBetween(card.lastReview!!, card.due) <= 100)
        }
    }

    // --- Retention curve -----------------------------------------------------------------------------

    @Test
    fun retrievabilityIsNinetyPercentAfterStabilityDays() {
        val model = Fsrs(FsrsParameters.DEFAULT_WEIGHTS)
        for (s in listOf(1.0, 3.0, 17.0, 200.0)) assertNear(0.9, model.retrievability(s, s), 1e-12)
    }

    @Test
    fun retentionCurveDecreasesMonotonically() {
        val model = Fsrs(FsrsParameters.DEFAULT_WEIGHTS)
        val curve = (0..365).map { model.retrievability(it.toDouble(), 10.0) }
        assertEquals(1.0, curve.first())
        assertTrue(curve.zipWithNext().all { (a, b) -> b < a })
    }

    @Test
    fun cardRetrievabilityIsZeroBeforeFirstReview() {
        val scheduler = FsrsScheduler()
        assertEquals(0.0, scheduler.retrievability(FsrsCard(due = t0), t0))
        val reviewed = scheduler.review(FsrsCard(due = t0), Rating.GOOD, t0)
        assertEquals(1.0, scheduler.retrievability(reviewed, t0 + 1.hours))
    }

    // --- Learning steps (our defaults: 10 min → 1 day, BRIEF §5.4) -------------------------------------

    @Test
    fun defaultLearningStepsTenMinutesThenOneDay() {
        val scheduler = FsrsScheduler(FsrsParameters(enableFuzzing = false))
        val new = FsrsCard(due = t0)

        val again = scheduler.review(new, Rating.AGAIN, t0)
        assertEquals(CardState.LEARNING, again.state)
        assertEquals(0, again.step)
        assertEquals(t0 + 10.minutes, again.due)

        val hard = scheduler.review(new, Rating.HARD, t0)
        assertEquals(t0 + (10.minutes + 1.days) / 2, hard.due)

        val good = scheduler.review(new, Rating.GOOD, t0)
        assertEquals(CardState.LEARNING, good.state)
        assertEquals(1, good.step)
        assertEquals(t0 + 1.days, good.due)

        val graduated = scheduler.review(good, Rating.GOOD, good.due)
        assertEquals(CardState.REVIEW, graduated.state)
        assertEquals(null, graduated.step)
        assertTrue(graduated.due > good.due + 1.days)

        val easy = scheduler.review(new, Rating.EASY, t0)
        assertEquals(CardState.REVIEW, easy.state)
        assertEquals(1, new.let { scheduler.review(it, Rating.GOOD, t0) }.reps)
    }

    @Test
    fun lapseGoesToRelearningAndCountsLapses() {
        val scheduler = FsrsScheduler(FsrsParameters(enableFuzzing = false))
        var card = scheduler.review(FsrsCard(due = t0), Rating.EASY, t0)
        card = scheduler.review(card, Rating.GOOD, card.due)
        val lapsed = scheduler.review(card, Rating.AGAIN, card.due)
        assertEquals(CardState.RELEARNING, lapsed.state)
        assertEquals(1, lapsed.lapses)
        assertEquals(lapsed.lastReview!! + 10.minutes, lapsed.due)
        assertTrue(lapsed.stability!! < card.stability!!)
        val back = scheduler.review(lapsed, Rating.GOOD, lapsed.due)
        assertEquals(CardState.REVIEW, back.state)
        assertEquals(1, back.lapses)
        assertEquals(4, back.reps)
    }

    @Test
    fun noRelearningStepsKeepsCardInReview() {
        val scheduler = FsrsScheduler(FsrsParameters(relearningSteps = emptyList(), enableFuzzing = false))
        val card = scheduler.review(FsrsCard(due = t0), Rating.EASY, t0)
        val lapsed = scheduler.review(card, Rating.AGAIN, card.due)
        assertEquals(CardState.REVIEW, lapsed.state)
        assertEquals(1, lapsed.lapses)
    }

    // --- Preview, fuzz, replay -------------------------------------------------------------------------

    @Test
    fun previewOrdersButtons() {
        val scheduler = FsrsScheduler()
        var card = scheduler.review(FsrsCard(due = t0), Rating.EASY, t0)
        card = scheduler.review(card, Rating.GOOD, card.due)
        val preview = scheduler.preview(card, card.due)
        assertEquals(Rating.entries.toSet(), preview.keys)
        val dues = Rating.entries.map { preview.getValue(it).due }
        assertTrue(dues.zipWithNext().all { (a, b) -> a < b }, "Again < Hard < Good < Easy: $dues")
        assertEquals(CardState.RELEARNING, preview.getValue(Rating.AGAIN).state)
    }

    @Test
    fun fuzzStaysWithinReferenceRange() {
        // Same card, same review; only the last interval is fuzzed.
        val plain = FsrsScheduler(FsrsParameters(enableFuzzing = false))
        val card = plain.review(FsrsCard(due = t0), Rating.EASY, t0)
        val nominal = wholeDaysBetween(card.due, plain.review(card, Rating.GOOD, card.due).due)
        // py-fsrs fuzz range: 1 + 0.15 per day in [2.5, 7) + 0.1 per day in [7, 20) + 0.05 per day beyond 20.
        val n = nominal.toDouble()
        val delta = 1 + 0.15 * (minOf(n, 7.0) - 2.5).coerceAtLeast(0.0) + 0.1 * (minOf(n, 20.0) - 7).coerceAtLeast(0.0) +
            0.05 * (n - 20).coerceAtLeast(0.0)
        val fuzzy = FsrsScheduler(FsrsParameters())
        val seen = (0 until 300).map { seed ->
            wholeDaysBetween(card.due, fuzzy.review(card, Rating.GOOD, card.due, Random(seed)).due)
        }.toSet()
        assertTrue(nominal > 20, "nominal $nominal")
        assertTrue(seen.size > 3, "fuzz should vary intervals: $seen")
        assertTrue(seen.all { it >= kotlin.math.round(n - delta).toLong() && it <= kotlin.math.round(n + delta).toLong() + 1 }, "$seen vs $nominal±$delta")
    }

    @Test
    fun fuzzIsDeterministicForAGivenRandom() {
        val a = FsrsScheduler(random = Random(7))
        val b = FsrsScheduler(random = Random(7))
        var ca = FsrsCard(due = t0)
        var cb = FsrsCard(due = t0)
        repeat(8) {
            ca = a.review(ca, Rating.GOOD, ca.due)
            cb = b.review(cb, Rating.GOOD, cb.due)
        }
        assertEquals(ca, cb)
    }

    @Test
    fun replayIsDeterministicAndMatchesSequentialReviews() {
        val scheduler = FsrsScheduler()
        val ratings = listOf(Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.AGAIN, Rating.GOOD, Rating.HARD, Rating.EASY, Rating.GOOD)
        // Build a realistic history by reviewing on each due date with the deterministic fuzz scheme.
        var card = FsrsCard(due = t0)
        val history = ArrayList<Pair<Instant, Rating>>()
        ratings.forEachIndexed { i, r ->
            val at = if (i == 0) t0 else card.due
            card = scheduler.review(card, r, at, Random(FsrsScheduler.fuzzSeed("card-1", i)))
            history += at to r
        }
        val replayed = scheduler.replay("card-1", t0, history.shuffled(Random(3)))
        assertEquals(card, replayed)
        assertEquals(replayed, FsrsScheduler(random = Random(99)).replay("card-1", t0, history))
        // Different card ids fuzz differently (so all cards don't bunch up on the same day).
        assertNotEquals(FsrsScheduler.fuzzSeed("card-1", 3), FsrsScheduler.fuzzSeed("card-2", 3))
    }

    @Test
    fun parameterValidation() {
        assertFailsWith<IllegalArgumentException> { FsrsParameters(weights = List(20) { 0.5 }) }
        assertFailsWith<IllegalArgumentException> {
            FsrsParameters(weights = FsrsParameters.DEFAULT_WEIGHTS.toMutableList().also { it[4] = 11.0 })
        }
    }

    // --- HARD / same-day vectors (BRIEF_V2 F-18) ---------------------------------------------------------
    // Expected values computed with the formulas of py-fsrs v6.3.2 (fsrs/scheduler.py: _short_term_stability
    // clamps the increase to ≥ 1 for Hard, Good and Easy; _next_difficulty mean-reverts towards the *unclamped*
    // _initial_difficulty(Easy, clamp=False)) and its DEFAULT_PARAMETERS (DECISIONS D-048).

    private fun memoryStates(seq: List<Pair<Int, Rating>>): List<Pair<Double?, Double?>> {
        val scheduler = FsrsScheduler(FsrsParameters(enableFuzzing = false))
        var card = FsrsCard(due = t0)
        return seq.map { (minutes, rating) ->
            card = scheduler.review(card, rating, t0 + minutes.minutes)
            card.stability to card.difficulty
        }
    }

    @Test
    fun pyFsrsSameDayHardAgainSequence() {
        // Good, then same-day Hard, Again, Hard; next-day Hard; Good three days later.
        val states = memoryStates(
            listOf(0 to Rating.GOOD, 1 to Rating.HARD, 5 to Rating.AGAIN, 15 to Rating.HARD, 1455 to Rating.HARD, 5775 to Rating.GOOD),
        )
        val expected = listOf(
            2.3065000000 to 2.1181039705,
            2.3065000000 to 4.7528584885,
            0.7750839829 to 8.2605286350,
            0.7750839829 to 8.8304862179,
            1.4533907619 to 9.2088506214,
            3.5685569321 to 9.1948701401,
        )
        states.zip(expected).forEach { (actual, want) ->
            assertNear(want.first, actual.first, 1e-8)
            assertNear(want.second, actual.second, 1e-8)
        }
    }

    @Test
    fun pyFsrsSameDayAgainFromFirstAgain() {
        val states = memoryStates(listOf(0 to Rating.AGAIN, 10 to Rating.AGAIN, 20 to Rating.GOOD, 2900 to Rating.HARD))
        val expected = listOf(
            0.2120000000 to 6.4133000000,
            0.0833567171 to 8.8063044689,
            0.1031406501 to 8.7927265337,
            0.5483055528 to 9.1837839834,
        )
        states.zip(expected).forEach { (actual, want) ->
            assertNear(want.first, actual.first, 1e-8)
            assertNear(want.second, actual.second, 1e-8)
        }
    }

    @Test
    fun pyFsrsMemoryFunctions() {
        val model = Fsrs(FsrsParameters.DEFAULT_WEIGHTS)
        assertNear(1.5968179980, model.shortTermStability(5.0, Rating.AGAIN), 1e-9)
        assertNear(0.212, model.shortTermStability(0.212, Rating.HARD), 1e-12) // increase < 1 is clamped for Hard
        assertNear(6.6659953693, model.nextDifficulty(5.0, Rating.HARD), 1e-9)
        assertNear(3.3144613693, model.nextDifficulty(5.0, Rating.EASY), 1e-9)
        assertNear(-4.7716307032, model.initialDifficulty(Rating.EASY, clamp = false), 1e-9)
    }
}
