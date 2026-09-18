package app.tsumugi.srs

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.time.TimeSource

class FsrsOptimizerTest {

    private val t0 = Instant.parse("2025-01-01T09:00:00Z")

    /** A learner whose memory truly follows these weights: faster initial learning, steeper forgetting. */
    private val trueWeights = FsrsParameters.DEFAULT_WEIGHTS.toMutableList().apply {
        this[0] = 0.6; this[1] = 2.5; this[2] = 5.0; this[3] = 15.0
        this[8] = 1.4; this[11] = 2.2; this[20] = 0.4
    }

    /**
     * Synthetic review log: each card is reviewed around its due date (0.4×–2.5× the scheduled interval, so
     * retrievability varies), recall is drawn from the true model's retrievability, and the rating follows recall.
     */
    private fun simulate(cards: IntRange, seed: Int): List<ReviewLogEntry> {
        val rng = Random(seed)
        val scheduler = FsrsScheduler(FsrsParameters(trueWeights, learningSteps = emptyList(), enableFuzzing = false))
        val log = ArrayList<ReviewLogEntry>()
        for (c in cards) {
            val id = "card-$c"
            var now = t0 + (c % 50).hours
            var card = scheduler.review(FsrsCard(due = now), Rating.GOOD, now)
            log += ReviewLogEntry(id, Rating.GOOD, now)
            repeat(10 + rng.nextInt(6)) {
                val scheduled = card.due - card.lastReview!!
                now = card.lastReview!! + scheduled * (0.4 + rng.nextDouble() * 2.1)
                val recalled = rng.nextDouble() < scheduler.retrievability(card, now)
                val rating = when {
                    !recalled -> Rating.AGAIN
                    rng.nextDouble() < 0.15 -> Rating.HARD
                    rng.nextDouble() < 0.15 -> Rating.EASY
                    else -> Rating.GOOD
                }
                card = scheduler.review(card, rating, now)
                log += ReviewLogEntry(id, rating, now)
            }
        }
        return log
    }

    @Test
    fun convergesOnSyntheticLogsAndGeneralizes() {
        val train = simulate(0 until 400, seed = 1)
        val heldOut = simulate(400 until 550, seed = 2)
        assertTrue(train.size >= FsrsOptimizer.MIN_REVIEWS)

        val started = TimeSource.Monotonic.markNow()
        val result = FsrsOptimizer.optimize(train)
        println("FsrsOptimizer: ${train.size} reviews in ${started.elapsedNow()}, loss ${result.lossBefore} -> ${result.lossAfter}")

        // BCE has an irreducible floor (the true model's own loss); the fit must close most of the gap to it.
        val trueTrainLoss = FsrsOptimizer.loss(train, trueWeights)
        println("train loss: defaults ${result.lossBefore}, fitted ${result.lossAfter}, true $trueTrainLoss")
        val closed = (result.lossBefore - result.lossAfter) / (result.lossBefore - trueTrainLoss)
        assertTrue(closed > 0.6, "closed only ${(closed * 100).toInt()}% of the gap to the true model")
        val defaultLoss = FsrsOptimizer.loss(heldOut, FsrsParameters.DEFAULT_WEIGHTS)
        val fittedLoss = FsrsOptimizer.loss(heldOut, result.weights)
        val trueLoss = FsrsOptimizer.loss(heldOut, trueWeights)
        println("held-out loss: defaults $defaultLoss, fitted $fittedLoss, true $trueLoss")
        assertTrue(fittedLoss < defaultLoss, "held-out: fitted $fittedLoss vs defaults $defaultLoss")
        // Fitted weights must be valid scheduler parameters.
        FsrsScheduler(FsrsParameters(result.weights))
    }

    @Test
    fun lossNeverGetsWorseAndHandlesTinyLogs() {
        val empty = FsrsOptimizer.optimize(emptyList())
        assertEquals(FsrsParameters.DEFAULT_WEIGHTS, empty.weights)
        assertEquals(0, empty.reviewCount)

        val small = simulate(0 until 5, seed = 3)
        val result = FsrsOptimizer.optimize(small)
        assertTrue(result.lossAfter <= result.lossBefore)
        assertTrue(result.reviewCount in 1 until small.size)
    }

    @Test
    fun gradientMatchesFiniteDifferences() {
        val log = simulate(0 until 20, seed = 4)
        val w = FsrsParameters.DEFAULT_WEIGHTS
        // The exact (dual-number) gradient must agree with central finite differences of the loss.
        for (k in listOf(0, 2, 8, 11, 20)) {
            val h = 1e-5
            val plus = FsrsOptimizer.loss(log, w.toMutableList().also { it[k] += h })
            val minus = FsrsOptimizer.loss(log, w.toMutableList().also { it[k] -= h })
            val numeric = (plus - minus) / (2 * h)
            val analytic = FsrsOptimizer.gradient(log, w)[k]
            assertTrue(kotlin.math.abs(numeric - analytic) <= 1e-4 + 1e-3 * kotlin.math.abs(numeric), "w[$k]: $analytic vs $numeric")
        }
    }
}
