// Algorithm ported from py-fsrs (MIT), © Open Spaced Repetition — https://github.com/open-spaced-repetition/py-fsrs
package app.tsumugi.srs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class Rating(val value: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

enum class CardState { NEW, LEARNING, REVIEW, RELEARNING }

/**
 * FSRS memory state of one card. A NEW card behaves like LEARNING at step 0 (py-fsrs starts cards in Learning).
 * [step] is the index into the learning/relearning steps while in those states, null otherwise.
 */
data class FsrsCard(
    val state: CardState = CardState.NEW,
    val step: Int? = null,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Instant,
    val lastReview: Instant? = null,
    val reps: Int = 0,
    val lapses: Int = 0,
)

data class FsrsParameters(
    val weights: List<Double> = DEFAULT_WEIGHTS,
    val desiredRetention: Double = 0.9,
    val learningSteps: List<Duration> = listOf(10.minutes, 1.days),
    val relearningSteps: List<Duration> = listOf(10.minutes),
    val maximumIntervalDays: Int = 36500,
    val enableFuzzing: Boolean = true,
) {
    init {
        require(weights.size == WEIGHT_COUNT) { "expected $WEIGHT_COUNT FSRS weights, got ${weights.size}" }
        weights.forEachIndexed { i, w ->
            require(w in LOWER_BOUNDS[i]..UPPER_BOUNDS[i]) { "weights[$i] = $w is out of bounds (${LOWER_BOUNDS[i]}, ${UPPER_BOUNDS[i]})" }
        }
    }

    companion object {
        const val WEIGHT_COUNT = 21
        const val DEFAULT_DECAY = 0.1542

        /** FSRS-6 defaults (py-fsrs DEFAULT_PARAMETERS). */
        val DEFAULT_WEIGHTS: List<Double> = listOf(
            0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
            1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, DEFAULT_DECAY,
        )

        val LOWER_BOUNDS: List<Double> = listOf(
            Fsrs.STABILITY_MIN, Fsrs.STABILITY_MIN, Fsrs.STABILITY_MIN, Fsrs.STABILITY_MIN, 1.0, 0.001, 0.001,
            0.001, 0.0, 0.0, 0.001, 0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
        )

        val UPPER_BOUNDS: List<Double> = listOf(
            100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8, 3.5, 5.0, 0.25, 0.9, 4.0, 1.0, 6.0,
            2.0, 2.0, 0.8, 0.8,
        )
    }
}

/**
 * The FSRS-6 memory model: pure functions of the weights. Shared by [FsrsScheduler] and [FsrsOptimizer]
 * (which re-implements the same formulas over dual numbers for gradients).
 */
class Fsrs(private val w: List<Double>) {
    private val decay = -w[20]
    private val factor = 0.9.pow(1 / decay) - 1

    /** Probability of recall after [elapsedDays] whole days with stability [stability]. */
    fun retrievability(elapsedDays: Double, stability: Double): Double =
        (1 + factor * elapsedDays / stability).pow(decay)

    fun initialStability(rating: Rating): Double = max(w[rating.value - 1], STABILITY_MIN)

    fun initialDifficulty(rating: Rating, clamp: Boolean = true): Double {
        val d = w[4] - exp(w[5] * (rating.value - 1)) + 1
        return if (clamp) clampDifficulty(d) else d
    }

    fun nextDifficulty(difficulty: Double, rating: Rating): Double {
        val delta = -(w[6] * (rating.value - 3))
        val damped = difficulty + (10.0 - difficulty) * delta / 9.0
        val reverted = w[7] * initialDifficulty(Rating.EASY, clamp = false) + (1 - w[7]) * damped
        return clampDifficulty(reverted)
    }

    fun shortTermStability(stability: Double, rating: Rating): Double {
        var increase = exp(w[17] * (rating.value - 3 + w[18])) * stability.pow(-w[19])
        if (rating != Rating.AGAIN) increase = max(increase, 1.0)
        return max(stability * increase, STABILITY_MIN)
    }

    fun nextStability(difficulty: Double, stability: Double, retrievability: Double, rating: Rating): Double {
        val next = if (rating == Rating.AGAIN) {
            val longTerm = w[11] * difficulty.pow(-w[12]) * ((stability + 1).pow(w[13]) - 1) * exp((1 - retrievability) * w[14])
            val shortTerm = stability / exp(w[17] * w[18])
            min(longTerm, shortTerm)
        } else {
            val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
            val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
            stability * (1 + exp(w[8]) * (11 - difficulty) * stability.pow(-w[9]) *
                (exp((1 - retrievability) * w[10]) - 1) * hardPenalty * easyBonus)
        }
        return max(next, STABILITY_MIN)
    }

    /** Whole-day interval that brings retrievability down to [desiredRetention]. */
    fun nextIntervalDays(stability: Double, desiredRetention: Double, maximumIntervalDays: Int): Int {
        val raw = stability / factor * (desiredRetention.pow(1 / decay) - 1)
        return round(raw).toInt().coerceAtLeast(1).coerceAtMost(maximumIntervalDays)
    }

    companion object {
        const val STABILITY_MIN = 0.001
        const val MIN_DIFFICULTY = 1.0
        const val MAX_DIFFICULTY = 10.0

        fun clampDifficulty(d: Double): Double = d.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY)
    }
}

/** Whole days between two instants, floored (matches Python's `timedelta.days`). */
internal fun wholeDaysBetween(from: Instant, to: Instant): Long =
    (to - from).inWholeMilliseconds.floorDiv(86_400_000L)

class FsrsScheduler(
    val parameters: FsrsParameters = FsrsParameters(),
    private val random: Random = Random.Default,
) {
    private val model = Fsrs(parameters.weights)

    fun retrievability(card: FsrsCard, now: Instant): Double {
        val last = card.lastReview ?: return 0.0
        val s = card.stability ?: return 0.0
        return model.retrievability(max(0L, wholeDaysBetween(last, now)).toDouble(), s)
    }

    fun review(card: FsrsCard, rating: Rating, now: Instant): FsrsCard = review(card, rating, now, random)

    /** [review] with an explicit fuzz source (used by [replay] so fuzz is reproducible). */
    fun review(card: FsrsCard, rating: Rating, now: Instant, fuzz: Random): FsrsCard {
        val daysSince = card.lastReview?.let { wholeDaysBetween(it, now) }
        val sameDay = daysSince != null && daysSince < 1
        val s0 = card.stability
        val d0 = card.difficulty

        // Memory state (identical for every card state).
        val (stability, difficulty) = when {
            s0 == null || d0 == null -> model.initialStability(rating) to model.initialDifficulty(rating)
            sameDay -> model.shortTermStability(s0, rating) to model.nextDifficulty(d0, rating)
            else -> model.nextStability(d0, s0, retrievability(card, now), rating) to model.nextDifficulty(d0, rating)
        }

        var state = if (card.state == CardState.NEW) CardState.LEARNING else card.state
        var step: Int? = if (card.state == CardState.NEW) 0 else card.step
        var lapses = card.lapses
        val interval: Duration

        fun graduate(): Duration {
            state = CardState.REVIEW
            step = null
            return model.nextIntervalDays(stability, parameters.desiredRetention, parameters.maximumIntervalDays).days
        }

        when (state) {
            CardState.LEARNING, CardState.RELEARNING -> {
                val steps = if (state == CardState.LEARNING) parameters.learningSteps else parameters.relearningSteps
                val current = step ?: 0
                interval = if (steps.isEmpty() || (current >= steps.size && rating != Rating.AGAIN)) {
                    graduate()
                } else {
                    when (rating) {
                        Rating.AGAIN -> { step = 0; steps[0] }
                        Rating.HARD -> when {
                            current == 0 && steps.size == 1 -> steps[0] * 1.5
                            current == 0 -> (steps[0] + steps[1]) / 2
                            else -> steps[current]
                        }
                        Rating.GOOD -> if (current + 1 == steps.size) graduate() else { step = current + 1; steps[current + 1] }
                        Rating.EASY -> graduate()
                    }
                }
            }
            CardState.REVIEW -> {
                interval = if (rating == Rating.AGAIN) {
                    lapses++
                    if (parameters.relearningSteps.isEmpty()) {
                        model.nextIntervalDays(stability, parameters.desiredRetention, parameters.maximumIntervalDays).days
                    } else {
                        state = CardState.RELEARNING
                        step = 0
                        parameters.relearningSteps[0]
                    }
                } else {
                    model.nextIntervalDays(stability, parameters.desiredRetention, parameters.maximumIntervalDays).days
                }
            }
            CardState.NEW -> error("unreachable")
        }

        val finalInterval = if (parameters.enableFuzzing && state == CardState.REVIEW) fuzzed(interval, fuzz) else interval
        return FsrsCard(
            state = state,
            step = step,
            stability = stability,
            difficulty = difficulty,
            due = now + finalInterval,
            lastReview = now,
            reps = card.reps + 1,
            lapses = lapses,
        )
    }

    /** What each answer button would do right now (nominal intervals, without fuzz). */
    fun preview(card: FsrsCard, now: Instant): Map<Rating, FsrsCard> {
        val noFuzz = if (parameters.enableFuzzing) FsrsScheduler(parameters.copy(enableFuzzing = false)) else this
        return Rating.entries.associateWith { noFuzz.review(card, it, now) }
    }

    /**
     * Recomputes a card from its full review history. Deterministic: fuzz for review i comes from
     * [fuzzSeed] (cardId, i), so every device that holds the same reviews computes the same card.
     */
    fun replay(cardId: String, created: Instant, reviews: List<Pair<Instant, Rating>>): FsrsCard =
        reviews.sortedBy { it.first }.foldIndexed(FsrsCard(due = created)) { i, card, (at, rating) ->
            review(card, rating, at, Random(fuzzSeed(cardId, i)))
        }

    private fun fuzzed(interval: Duration, fuzz: Random): Duration {
        val days = interval.inWholeDays
        if (days < 2.5) return interval
        var delta = 1.0
        for ((start, end, factor) in FUZZ_RANGES) {
            delta += factor * max(min(days.toDouble(), end) - start, 0.0)
        }
        var minIvl = max(2, round(days - delta).toInt())
        val maxIvl = min(round(days + delta).toInt(), parameters.maximumIntervalDays)
        minIvl = min(minIvl, maxIvl)
        val fuzzedDays = min(round(fuzz.nextDouble() * (maxIvl - minIvl + 1) + minIvl).toInt(), parameters.maximumIntervalDays)
        return fuzzedDays.days
    }

    companion object {
        private val FUZZ_RANGES = listOf(
            Triple(2.5, 7.0, 0.15),
            Triple(7.0, 20.0, 0.1),
            Triple(20.0, Double.POSITIVE_INFINITY, 0.05),
        )

        /** Platform-independent seed for the fuzz of review [index] of [cardId] (FNV-1a over UTF-16 units). */
        fun fuzzSeed(cardId: String, index: Int): Long {
            var h = -0x340d631b7bdddcdbL // FNV offset basis 0xcbf29ce484222325
            for (c in cardId) {
                h = (h xor c.code.toLong()) * 0x100000001b3L
            }
            h = (h xor index.toLong()) * 0x100000001b3L
            return h
        }
    }
}
