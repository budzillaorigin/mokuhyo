package app.tsumugi.integrations.wanikani

import app.tsumugi.srs.CardState
import app.tsumugi.srs.FsrsCard
import app.tsumugi.srs.FsrsParameters
import app.tsumugi.srs.FsrsScheduler
import app.tsumugi.srs.Rating
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Turns a WaniKani SRS stage into a plausible FSRS review history (BRIEF §5.4, §8.3).
 *
 * WaniKani's assignments only say which stage an item is at, not when each review happened. We synthesize
 * the shortest run of successful reviews (GOOD at the FSRS due time; HARD or an earlier time when GOOD would
 * overshoot the stage — see [maxStabilityDays]) whose resulting stability lands in the
 * matching Tsumugi stage (Apprentice 1–4 → Apprentice, Guru 5–6 → Guru, Master 7 → Master,
 * Enlightened 8 → Enlightened, Burned 9 → Burned). The run is then shifted so its last review falls at
 * [syntheticReviews]'s `lastReviewAt`.
 *
 * Lapse heuristic: the share of wrong answers from review_statistics (incorrect / (correct + incorrect)) is
 * applied to the length of that lapse-free run, rounded, capped at [MAX_LAPSES]; that many AGAIN reviews are
 * inserted once the card has graduated, and GOOD reviews continue until the target stability is reached again.
 * So a frequently-missed item keeps its stage but gets a higher FSRS difficulty (shorter future intervals).
 */
object WaniKaniStageMapping {

    /** WaniKani's interval after reaching each stage (stage 9 = burned, no further reviews). */
    val intervals: Map<Int, Duration> = mapOf(
        1 to 4.hours, 2 to 8.hours, 3 to 1.days, 4 to 2.days, 5 to 7.days, 6 to 14.days, 7 to 30.days, 8 to 120.days,
    )

    const val BURNED = 9
    const val MAX_LAPSES = 3
    private const val MAX_REVIEWS = 40

    /** Target FSRS stability (days) per WK stage; each sits inside the matching Tsumugi [app.tsumugi.domain.Stage]. */
    fun targetStabilityDays(stage: Int): Double = when (stage) {
        in 1..4 -> 0.0 // one review: FSRS's initial stability stays below Guru's 3 days
        5 -> 4.0
        6 -> 10.0
        7 -> 25.0
        8 -> 70.0
        BURNED -> 200.0
        else -> 0.0
    }

    /** Stability the synthetic history must stay below: the start of the next Tsumugi stage. */
    fun maxStabilityDays(stage: Int): Double = when (stage) {
        in 1..4 -> 3.0
        5, 6 -> 21.0
        7 -> 60.0
        8 -> 180.0
        else -> Double.POSITIVE_INFINITY
    }

    /** Lapses to seed for an item with these answer counts (see class KDoc). */
    fun lapsesFor(correct: Int, incorrect: Int, seedLength: Int): Int {
        val total = correct + incorrect
        if (total == 0 || incorrect == 0) return 0
        return (incorrect.toDouble() / total * seedLength).roundToInt().coerceIn(0, MAX_LAPSES)
    }

    /**
     * Review history (time, rating) for a card at WK [stage] whose last review was [lastReviewAt].
     * [startedAt] is only a lower bound for sanity: histories are not stretched to fit it.
     * [parameters] must be the scheduler parameters the card will be replayed with.
     */
    fun syntheticReviews(
        stage: Int,
        startedAt: Instant,
        lastReviewAt: Instant,
        lapses: Int = 0,
        parameters: FsrsParameters = FsrsParameters(),
    ): List<Pair<Instant, Rating>> {
        if (stage <= 0) return emptyList()
        val scheduler = FsrsScheduler(parameters.copy(enableFuzzing = false))
        val target = targetStabilityDays(stage)
        val origin = Instant.fromEpochSeconds(0)
        val history = ArrayList<Pair<Instant, Rating>>()
        var card = FsrsCard(due = origin)
        var remainingLapses = lapses.coerceIn(0, MAX_LAPSES)

        val ceiling = maxStabilityDays(stage)

        fun review(rating: Rating) {
            val at = if (history.isEmpty()) origin else maxOf(card.due, history.last().first)
            card = scheduler.review(card, rating, at)
            history += at to rating
        }

        /**
         * A successful review that doesn't overshoot [ceiling]: GOOD at the due time if that stays below it,
         * otherwise HARD, otherwise an earlier (smaller-gain) review time. FSRS gains are large for long
         * intervals, so without this a Guru item could land in Master.
         */
        fun recall() {
            val last = history.last().first
            for (fraction in listOf(1.0, 0.75, 0.5, 0.35, 0.25, 0.15, 0.08)) {
                val at = last + (maxOf(card.due, last) - last) * fraction
                for (rating in listOf(Rating.GOOD, Rating.HARD)) {
                    val next = scheduler.review(card, rating, at)
                    if ((next.stability ?: 0.0) < ceiling) {
                        card = next
                        history += at to rating
                        return
                    }
                }
            }
            review(Rating.GOOD)
        }

        review(Rating.GOOD)
        while (history.size < MAX_REVIEWS) {
            val reachedTarget = (card.stability ?: 0.0) >= target && card.state != CardState.RELEARNING
            if (remainingLapses > 0 && card.state == CardState.REVIEW) {
                review(Rating.AGAIN)
                remainingLapses--
            } else if (reachedTarget && (target == 0.0 || card.state == CardState.REVIEW)) {
                break
            } else {
                recall()
            }
        }
        val shift = maxOf(lastReviewAt, startedAt) - history.last().first
        return history.map { (at, rating) -> (at + shift) to rating }
    }

    /** Number of reviews in the lapse-free history for [stage] (input to [lapsesFor]). */
    fun seedLength(stage: Int, parameters: FsrsParameters = FsrsParameters()): Int =
        syntheticReviews(stage, Instant.fromEpochSeconds(0), Instant.fromEpochSeconds(0), 0, parameters).size

    /**
     * When the last WK review probably happened: the next review time minus the stage interval
     * (burned items: when they were burned), never after [now].
     */
    fun estimateLastReview(assignment: WkAssignment, now: Instant): Instant {
        val parsed = { s: String? -> s?.let { runCatching { Instant.parse(it) }.getOrNull() } }
        val estimate = when {
            assignment.srsStage >= BURNED -> parsed(assignment.burnedAt) ?: parsed(assignment.passedAt)
            else -> parsed(assignment.availableAt)?.let { at -> intervals[assignment.srsStage]?.let { at - it } }
        } ?: now
        return minOf(estimate, now)
    }
}
