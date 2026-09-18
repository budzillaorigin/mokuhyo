package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.Stage
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.SrsRepository
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathStatus
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

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
        assertEquals(
            listOf(TodayBlockKind.REVIEWS, TodayBlockKind.LESSONS, TodayBlockKind.GRAMMAR, TodayBlockKind.IMMERSION),
            plan.blocks.map { it.kind },
            "no reader text, dialogue or grammar examples: immersion shows its empty state, no shadowing",
        )
        val immersion = plan.block(TodayBlockKind.IMMERSION)!!
        assertEquals(null, immersion.launch)
        assertEquals(0, immersion.minutes)
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

    /** BRIEF_V2 F-19: a lesson is one item, however many cards it introduces; ghost introductions aren't lessons. */
    @Test
    fun lessonCountsAreDistinctItems() = runTest {
        clock.advance(7.days) // 2026-09-08: the "learn 25 new items" week
        val srs = SrsRepository(db, "device", clock)
        val kanji = listOf("k:日", "k:月").map {
            NewItem(it, ItemKind.KANJI, it.removePrefix("k:"), null, listOf("m"), listOf("r"), ItemSource.PACK, listOf(CardDirection.MEANING, CardDirection.READING))
        }
        srs.addItems(kanji)
        srs.introduce(kanji.flatMap { k -> k.directions.map { SrsRepository.cardId(k.id, it) } }) // 4 cards, 2 items
        val grammar = NewItem("g:tai", ItemKind.GRAMMAR, "〜たい", null, listOf("want"), emptyList(), ItemSource.PACK, listOf(CardDirection.CLOZE, CardDirection.GHOST))
        srs.addItems(listOf(grammar))
        srs.introduce(listOf(SrsRepository.cardId("g:tai", CardDirection.CLOZE), SrsRepository.cardId("g:tai", CardDirection.GHOST)))

        settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, "60")
        val plan = planner.plan(dueReviews = 0, path = status(), grammarAvailable = 3)
        assertEquals(15 - 2, plan.blocks[1].count, "60-minute target 15 minus 2 items learned today")
        assertEquals(3 - 1, plan.blocks[2].count, "one grammar point learned today")
        assertEquals("Learn 25 new items this week", plan.challenge.title)
        assertEquals(3, plan.challenge.progress, "2 kanji + 1 grammar point, not 6 introductions")
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
