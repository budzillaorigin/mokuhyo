package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class StatsServiceTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)
    private val settings = SettingsRepository(db, clock)
    private val stats = StatsService(db, srs, settings, clock) { TimeZone.UTC }
    private val card = SrsRepository.cardId("k:水", CardDirection.MEANING)

    private suspend fun setUp() {
        srs.addItems(listOf(NewItem("k:水", ItemKind.KANJI, "水", "すい", listOf("water"), listOf("すい"), ItemSource.PACK, listOf(CardDirection.MEANING))))
        srs.introduce(listOf(card))
    }

    private suspend fun reviewOnDay(correct: Boolean = true) {
        srs.review(card, if (correct) Rating.GOOD else Rating.AGAIN, correct = correct)
    }

    @Test
    fun streakCountsConsecutiveDaysAndLessonsDontCount() = runTest {
        setUp()
        assertEquals(0, stats.streak().current, "an introduction alone is not a study day")
        repeat(3) {
            reviewOnDay()
            clock.advance(1.days)
        }
        // Now it's the morning after the third day; nothing studied yet today.
        val s = stats.streak()
        assertEquals(3, s.current)
        assertEquals(false, s.studiedToday)
        reviewOnDay()
        assertEquals(4, stats.streak().current)
    }

    @Test
    fun missedDayBreaksStreakUnlessOnVacation() = runTest {
        setUp()
        reviewOnDay()
        clock.advance(1.days); reviewOnDay()
        clock.advance(3.days)
        assertEquals(0, stats.streak().current)
        assertEquals(2, stats.streak().longest)

        stats.setVacation(true)
        clock.advance(2.days)
        assertTrue(stats.streak().onVacation)
        assertTrue(stats.streak().current >= 1, "vacation days count")
    }

    @Test
    fun snapshotHeatmapAccuracyAndForecast() = runTest {
        setUp()
        reviewOnDay(correct = true)
        clock.advance(2.hours)
        reviewOnDay(correct = false)
        val snap = stats.snapshot(heatmapDays = 30)
        assertEquals(30, snap.heatmap.size)
        assertEquals(2, snap.heatmap.last().count)
        assertEquals(2, snap.reviewsToday)
        assertEquals(Accuracy(1, 2), snap.accuracy[ItemKind.KANJI])
        assertEquals(7, snap.forecast.size)
        assertEquals(1, snap.forecast.sumOf { it.count }, "the relearning card is due within the week")
    }

    /**
     * BRIEF_V2 F-27: day totals read from daily_stats match the old full scan of the review log, in zones with
     * half- and quarter-hour offsets, after undos (fast-path delete and tombstone) and after a rebuild.
     */
    @Test
    fun materializedStatsMatchTheReviewLog() = runTest {
        setUp()
        val random = kotlin.random.Random(7)
        repeat(120) {
            clock.advance(random.nextInt(1, 20 * 60).minutes)
            val outcome = srs.review(card, if (random.nextBoolean()) Rating.GOOD else Rating.AGAIN, correct = random.nextBoolean())
            when (random.nextInt(10)) {
                0 -> srs.undo(outcome) // never pushed: deleted
                1 -> { db.syncQueries.setPushing(1); srs.undo(outcome); db.syncQueries.setPushing(0) } // tombstoned
            }
        }
        for (zone in listOf("UTC", "Asia/Kolkata", "Asia/Kathmandu", "America/St_Johns", "Pacific/Chatham")) {
            val tz = TimeZone.of(zone)
            val service = StatsService(db, srs, settings, clock) { tz }
            val today = clock.now().toLocalDateTime(tz).date
            val fromLog = db.srsQueries.allReviews().executeAsList().filter { it.rating != 0L }
                .groupingBy { Instant.fromEpochMilliseconds(it.ts).toLocalDateTime(tz).date }.eachCount()
            val snap = service.snapshot(heatmapDays = 60)
            assertEquals(snap.heatmap.map { it.date to (fromLog[it.date] ?: 0) }, snap.heatmap.map { it.date to it.count }, zone)
            assertEquals(fromLog[today] ?: 0, snap.reviewsToday, zone)
            var run = 0
            var d = if (today in fromLog) today else today.minus(DatePeriod(days = 1))
            while (d in fromLog) { run++; d = d.minus(DatePeriod(days = 1)) }
            assertEquals(run, snap.streak.current, zone)
        }
        val before = db.srsQueries.statsSlotsSince(0).executeAsList()
        db.transaction { db.srsQueries.clearStats(); db.srsQueries.rebuildStats() }
        assertEquals(before, db.srsQueries.statsSlotsSince(0).executeAsList(), "trigger-maintained totals equal a rebuild")
    }
}
