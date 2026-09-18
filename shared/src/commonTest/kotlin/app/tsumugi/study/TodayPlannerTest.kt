package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.Stage
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathStatus
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodayPlannerTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val settings = SettingsRepository(db, clock)
    private val planner = TodayPlanner(db, settings, clock) { TimeZone.UTC }

    private fun status(level: Int = 3, lessons: Int = 40) =
        PathStatus(level, 60, 0.2, lessons, 0, emptyMap<Stage, Int>())

    @Test
    fun blocksInOrderWithBudget() = runTest {
        val plan = planner.plan(dueReviews = 50, path = status(), grammarAvailable = 3)
        assertEquals(listOf(TodayBlockKind.REVIEWS, TodayBlockKind.LESSONS, TodayBlockKind.GRAMMAR), plan.blocks.map { it.kind })
        assertEquals(20, plan.budgetMinutes)
        assertEquals(LearningPhase.FOUNDATIONS, plan.phase)
        assertEquals(6, plan.blocks[1].count, "20-minute budget → 6 lessons")
        assertEquals(1, plan.blocks[2].count)
        assertTrue(plan.plannedMinutes > 0)
    }

    @Test
    fun reviewsAreCappedByBudget() = runTest {
        settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, "10")
        val plan = planner.plan(dueReviews = 400, path = status(), grammarAvailable = 0)
        val reviews = plan.blocks.first()
        assertTrue(reviews.count < 400)
        assertEquals(0, plan.blocks[1].count, "big backlog pauses new lessons")
        assertEquals(2, plan.blocks.size, "no grammar block without a grammar pack")
    }

    @Test
    fun lessonTargetAdapts() {
        assertEquals(10, planner.lessonTarget(40, 0.95, 20))
        assertEquals(8, planner.lessonTarget(40, 0.8, 20))
        assertEquals(5, planner.lessonTarget(40, 0.6, 20))
        assertEquals(5, planner.lessonTarget(40, null, 100))
        assertEquals(0, planner.lessonTarget(60, 0.99, 200))
    }

    @Test
    fun phasesByLevel() {
        assertEquals(LearningPhase.FOUNDATIONS, LearningPhase.forLevel(1))
        assertEquals(LearningPhase.CORE, LearningPhase.forLevel(12))
        assertEquals(LearningPhase.ADVANCED, LearningPhase.forLevel(60))
    }
}
