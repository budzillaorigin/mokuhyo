package app.tsumugi.immersion

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathProgressStore
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.StatsService
import app.tsumugi.study.TodayBlockKind
import app.tsumugi.study.TodayPlanner
import app.tsumugi.sync.FakeSyncServer
import app.tsumugi.sync.SyncEngine
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** BRIEF_V2 §6.11: the immersion log, its target hooks, union + tombstone sync, and the roadmap. */
class ImmersionTest {
    private val clock = TestClock() // 2026-09-01T09:00Z

    private inner class Device(val id: String, server: FakeSyncServer? = null) {
        val driver = inMemoryDriver(TsumugiDatabase.Schema)
        val db = TsumugiDatabase(driver)
        val settings = SettingsRepository(db, clock)
        val log = ImmersionLog(db, id, settings, clock) { TimeZone.UTC }
        val srs = SrsRepository(db, id, clock)
        val engine = server?.let { SyncEngine(driver, db, srs, id, it, null, clock) }
    }

    @Test
    fun startStopLogsWallClockAndDropsAccidentalOpens() = runTest {
        val d = Device("a")
        val t = d.log.start(ImmersionOrigin.READER, ImmersionMode.ACTIVE, ref = "doc-1", title = "猫の話")
        clock.advance(12.minutes)
        val s = assertNotNull(d.log.stop(t))
        assertEquals(720, s.durationSeconds)
        assertEquals("2026-09-01", s.day)
        assertNull(d.log.stop(t), "a ticket stops once")

        val quick = d.log.start(ImmersionOrigin.MEDIA, ImmersionMode.PASSIVE)
        clock.advance(5.seconds)
        assertNull(d.log.stop(quick), "shorter than MIN_SECONDS is dropped")

        val forgotten = d.log.start(ImmersionOrigin.PODCAST, ImmersionMode.PASSIVE)
        clock.advance(20.hours)
        assertEquals(ImmersionLog.MAX_SECONDS, d.log.stop(forgotten)!!.durationSeconds, "a player left running is capped")
        assertTrue(d.log.runningTickets.isEmpty())
    }

    @Test
    fun daysSplitActivePassiveAndSourcesAndMeetTheTarget() = runTest {
        val d = Device("a")
        d.log.setTargetMinutes(30)
        d.log.report(ImmersionOrigin.MEDIA, ImmersionMode.ACTIVE, 20 * 60)
        d.log.report(ImmersionOrigin.PODCAST, ImmersionMode.PASSIVE, 15 * 60)
        d.log.addManual(LocalDate(2026, 8, 30), 45, ImmersionMode.ACTIVE, title = "Drama at a friend's")
        assertFailsWith<IllegalArgumentException> { d.log.addManual(LocalDate(2026, 9, 2), 10) }

        val days = d.log.days(3)
        assertEquals(listOf("2026-08-30", "2026-08-31", "2026-09-01"), days.map { it.date.toString() })
        assertEquals(45, days[0].activeMinutes)
        assertEquals(listOf(SourceMinutes(ImmersionOrigin.MANUAL, 45)), days[0].bySource)
        assertEquals(0, days[1].totalMinutes)
        assertEquals(20, days[2].activeMinutes)
        assertEquals(15, days[2].passiveMinutes)
        assertEquals(listOf(ImmersionOrigin.MEDIA, ImmersionOrigin.PODCAST), days[2].bySource.map { it.source })
        assertTrue(days[2].targetMet)
        assertTrue(d.log.targetProgress().met)
        assertEquals((80 * 60) / 3600.0, d.log.totalHours(), 1e-9)

        val stats = StatsService(d.db, d.srs, d.settings, clock) { TimeZone.UTC }
        val heat = stats.immersionHeatmap(7)
        assertEquals(7, heat.size)
        assertEquals(35, heat.last().totalMinutes)
        assertEquals(30, heat.last().targetMinutes)
    }

    @Test
    fun meetingTheTargetFinishesTodaysImmersionBlock() = runTest {
        val d = Device("a")
        d.settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, "20")
        d.log.setTargetMinutes(10)
        val planner = TodayPlanner(d.db, d.settings, clock) { TimeZone.UTC }.also { p -> p.immersionProgress = { d.log.targetProgress() } }
        assertFalse(planner.plan(0, null, 0).block(TodayBlockKind.IMMERSION)!!.done)
        d.log.report(ImmersionOrigin.READER, ImmersionMode.ACTIVE, 11 * 60)
        assertTrue(planner.plan(0, null, 0).block(TodayBlockKind.IMMERSION)!!.done)
        assertEquals(listOf("IMMERSION"), d.db.studyQueries.blocksDoneOn("2026-09-01").executeAsList(), "recorded for the weekly challenge")
    }

    @Test
    fun sessionsSyncByUnionAndDeletesAreTombstonesEverywhere() = runTest {
        val server = FakeSyncServer()
        val a = Device("device-a", server)
        val b = Device("device-b", server)
        val s1 = a.log.report(ImmersionOrigin.MEDIA, ImmersionMode.ACTIVE, 600)!!
        b.log.report(ImmersionOrigin.READER, ImmersionMode.ACTIVE, 300)
        a.engine!!.sync(); b.engine!!.sync(); a.engine.sync()
        for (d in listOf(a, b)) assertEquals(900, d.log.days(1).single().totalMinutes * 60, d.id)

        // Delete after it was pushed: the tombstone reaches the other device.
        clock.advance(1.minutes)
        b.log.delete(s1.id)
        b.engine.sync(); a.engine.sync()
        for (d in listOf(a, b)) assertEquals(5, d.log.days(1).single().totalMinutes, d.id)
        // Syncing again changes nothing.
        a.engine.sync(); b.engine.sync()
        assertEquals(5, b.log.days(1).single().totalMinutes)
    }

    @Test
    fun roadmapStagesFollowMilestonesAndNeverGoBack() = runTest {
        val beginner = Roadmap.evaluate(RoadmapInputs(knownWords = 120, immersionHours = 3.0))
        assertEquals(RoadmapStage.FOUNDATIONS, beginner.current)
        assertEquals("s1-words", beginner.next!!.id)
        assertEquals(120.0 / 800, beginner.next!!.progress, 1e-9)

        // Stage 2's graded-reader milestone isn't measurable yet (Phase 12): it doesn't hold the learner back.
        val mid = Roadmap.evaluate(RoadmapInputs(knownWords = 3200, immersionHours = 310.0))
        assertEquals(RoadmapStage.OUTPUT, mid.current)
        val readers = mid.stages[1].milestones.single { it.measure == MilestoneMeasure.READER_COMPREHENSION }
        assertNull(readers.current)
        assertFalse(readers.met)
        // Stage 3 needs an OPI estimate.
        assertEquals(RoadmapStage.OUTPUT, Roadmap.evaluate(RoadmapInputs(6500, 900.0, 85.0, null)).current)
        assertEquals(RoadmapStage.REFINEMENT, Roadmap.evaluate(RoadmapInputs(6500, 900.0, 85.0, IlrLevel.L2)).current)

        val d = Device("a")
        var words = 900
        d.log.addManual(LocalDate(2026, 8, 1), 360)
        repeat(4) { d.log.addManual(LocalDate(2026, 8, 2 + it), 360) } // 30 hours
        val service = RoadmapService(d.log, PathProgressStore(d.db, clock), knownWords = { words })
        assertEquals(RoadmapStage.COMPREHENSION, service.status().current)
        words = 400 // leeches dropped words below Guru
        assertEquals(RoadmapStage.COMPREHENSION, service.status().current, "rule 11: a reached stage is kept")
        assertEquals(2, PathProgressStore(d.db, clock).progress(Roadmap.TRACK).passedLevel)
        clock.advance(1.days)
    }
}
