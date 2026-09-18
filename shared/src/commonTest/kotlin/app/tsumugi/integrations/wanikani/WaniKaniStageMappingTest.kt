package app.tsumugi.integrations.wanikani

import app.tsumugi.domain.Stage
import app.tsumugi.srs.FsrsScheduler
import app.tsumugi.srs.Rating
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class WaniKaniStageMappingTest {

    private val last = Instant.parse("2026-09-01T09:00:00Z")
    private val started = Instant.parse("2025-01-01T00:00:00Z")
    private val scheduler = FsrsScheduler()

    private fun stageAfter(wkStage: Int, lapses: Int = 0): Pair<Stage?, Int> {
        val reviews = WaniKaniStageMapping.syntheticReviews(wkStage, started, last, lapses)
        val card = scheduler.replay("card", reviews.first().first, reviews)
        return Stage.of(card.stability, true) to card.lapses
    }

    @Test
    fun wkStagesLandInMatchingTsumugiStages() {
        for (s in 1..4) assertEquals(Stage.APPRENTICE, stageAfter(s).first, "WK stage $s")
        assertEquals(Stage.GURU, stageAfter(5).first)
        assertEquals(Stage.GURU, stageAfter(6).first)
        assertEquals(Stage.MASTER, stageAfter(7).first)
        assertEquals(Stage.ENLIGHTENED, stageAfter(8).first)
        assertEquals(Stage.BURNED, stageAfter(9).first)
    }

    @Test
    fun historyEndsAtLastReviewAndIsChronological() {
        val reviews = WaniKaniStageMapping.syntheticReviews(8, started, last)
        assertEquals(last, reviews.last().first)
        assertEquals(reviews.map { it.first }.sorted(), reviews.map { it.first })
        assertTrue(reviews.none { it.second == Rating.AGAIN }, "no lapses unless asked for")
    }

    @Test
    fun lapsesAreInsertedButStageKept() {
        val (stage, lapses) = stageAfter(6, lapses = 2)
        assertEquals(Stage.GURU, stage)
        assertEquals(2, lapses)
    }

    @Test
    fun lapseHeuristic() {
        assertEquals(0, WaniKaniStageMapping.lapsesFor(10, 0, 5))
        assertEquals(0, WaniKaniStageMapping.lapsesFor(0, 0, 5))
        assertEquals(2, WaniKaniStageMapping.lapsesFor(6, 4, 5))
        assertEquals(WaniKaniStageMapping.MAX_LAPSES, WaniKaniStageMapping.lapsesFor(0, 50, 9))
    }

    @Test
    fun estimatesLastReviewFromAvailableAt() {
        val now = Instant.parse("2026-09-01T09:00:00Z")
        val guru = WkAssignment(1, "kanji", 5, availableAt = "2026-09-06T09:00:00.000000Z", startedAt = "2026-01-01T00:00:00Z")
        assertEquals(Instant.parse("2026-08-30T09:00:00Z"), WaniKaniStageMapping.estimateLastReview(guru, now))
        val burned = WkAssignment(1, "kanji", 9, burnedAt = "2026-06-01T00:00:00Z", startedAt = "2026-01-01T00:00:00Z")
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), WaniKaniStageMapping.estimateLastReview(burned, now))
        val future = WkAssignment(1, "kanji", 1, availableAt = "2027-01-01T00:00:00Z", startedAt = "2026-01-01T00:00:00Z")
        assertEquals(now, WaniKaniStageMapping.estimateLastReview(future, now), "never after now")
        assertTrue(now - WaniKaniStageMapping.estimateLastReview(guru, now) < 3.days)
    }
}
