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
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

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
}
