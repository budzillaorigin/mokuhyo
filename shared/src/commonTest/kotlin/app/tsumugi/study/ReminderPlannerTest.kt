package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ReminderPlannerTest {

    private val clock = TestClock(Instant.parse("2026-09-01T12:00:00Z"))
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)
    private val settings = SettingsRepository(db, clock)
    private val planner = ReminderPlanner(db, settings, clock) { TimeZone.UTC }

    private suspend fun introduce(n: Int) {
        val items = (1..n).map { NewItem("k:$it", ItemKind.KANJI, "$it", null, listOf("m"), emptyList(), ItemSource.PACK, listOf(CardDirection.MEANING)) }
        srs.addItems(items)
        srs.introduce(items.map { SrsRepository.cardId(it.id, CardDirection.MEANING) })
    }

    @Test
    fun nothingDueMeansNoReminder() = runTest {
        assertNull(planner.next())
    }

    @Test
    fun remindsWhenThresholdReachedButNotInstantly() = runTest {
        introduce(3) // due at 12:10
        settings.put(SettingsRepository.REMINDER_THRESHOLD, "3")
        val r = assertNotNull(planner.next())
        assertEquals(clock.now() + 30.minutes, r.at, "at least 30 minutes out")
        assertEquals(3, r.dueCount)
    }

    @Test
    fun quietHoursPushToMorning() = runTest {
        val p = planner
        assertEquals(Instant.parse("2026-09-02T08:00:00Z"), p.outsideQuietHours(Instant.parse("2026-09-01T23:30:00Z"), 22 * 60, 8 * 60))
        assertEquals(Instant.parse("2026-09-02T08:00:00Z"), p.outsideQuietHours(Instant.parse("2026-09-02T03:00:00Z"), 22 * 60, 8 * 60))
        assertEquals(Instant.parse("2026-09-01T12:00:00Z"), p.outsideQuietHours(Instant.parse("2026-09-01T12:00:00Z"), 22 * 60, 8 * 60))
        assertEquals(Instant.parse("2026-09-01T14:00:00Z"), p.outsideQuietHours(Instant.parse("2026-09-01T13:00:00Z"), 12 * 60, 14 * 60))
    }

    @Test
    fun disabledRemindersReturnNull() = runTest {
        introduce(1)
        settings.put(SettingsRepository.REMINDERS_ENABLED, "false")
        assertNull(planner.next())
    }
}
