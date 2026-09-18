package app.tsumugi.exam

import app.tsumugi.exam.jlpt.JlptBlueprints
import app.tsumugi.testing.TestClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ExamSessionTest {

    private val blueprint = JlptBlueprints.parse(JlptScoringTest.BLUEPRINT_JSON).level(5)!!

    private fun item(id: String, type: String, level: String = "N5", passage: String? = null, exam: ExamKind = ExamKind.JLPT, verified: Boolean = false) =
        ExamItem(id, "test", exam, level, type, passage, "stem $id", listOf("a", "b", "c", "d"), 1, "", emptyList(), listOf("g:$id"), "llm", verified)

    /** Enough N5 items for every blueprint slot, plus a 3-question passage for comprehension_short. */
    private fun n5Pool(): List<ExamItem> = blueprint.sections.flatMap { it.items }.flatMap { spec ->
        if (spec.type == "comprehension_short") (1..3).map { item("cs$it", spec.type, passage = "p1") }
        else (1..spec.count + 2).map { item("${spec.type}-$it", spec.type) }
    }

    private val passages = mapOf("p1" to ExamPassage("p1", ExamKind.JLPT, "N5", "notice", "Notice", "おしらせ", emptyList(), "llm", false))

    @Test
    fun mockFollowsBlueprint() {
        val form = ExamAssembler.jlptMock(5, blueprint, n5Pool(), passages, Random(1))
        assertEquals(blueprint.sections.map { it.minutes }, form.sections.map { it.minutes })
        assertEquals(blueprint.itemCount, form.items.size)
        assertTrue(form.shortfalls.isEmpty())
        assertEquals(setOf("p1"), form.passages.keys)
        assertTrue(form.sections.last().listening)
    }

    @Test
    fun shortfallsAreReported() {
        val form = ExamAssembler.jlptMock(5, blueprint, n5Pool().filter { it.type != "utterance" }, passages, Random(1))
        assertEquals(listOf(Shortfall("utterance", 5, 0)), form.shortfalls)
    }

    @Test
    fun passageGroupsStayWhole() {
        val pool = (1..3).map { item("a$it", "comprehension_mid", passage = "A") } + (1..2).map { item("b$it", "comprehension_mid", passage = "B") }
        repeat(20) { seed ->
            val picked = ExamAssembler.pick(pool, 4, Random(seed))
            val byPassage = picked.groupBy { it.passageId }
            assertTrue(byPassage.all { (p, list) -> list.size == if (p == "A") 3 else 2 }, "seed $seed: $picked")
        }
    }

    @Test
    fun verifiedItemsArePreferred() {
        val pool = (1..10).map { item("u$it", "context") } + (1..3).map { item("v$it", "context", verified = true) }
        val picked = ExamAssembler.pick(pool, 3, Random(7))
        assertTrue(picked.all { it.verified })
    }

    @Test
    fun strictSectionsCloseWhenTimeRunsOut() {
        val clock = TestClock()
        val session = ExamSession(ExamAssembler.jlptMock(5, blueprint, n5Pool(), passages, Random(1)), blueprint, clock)
        assertEquals(20 * 60_000L, session.remainingMs())
        clock.advance(19.minutes)
        assertFalse(session.tick())
        clock.advance(1.minutes)
        assertTrue(session.tick())
        assertEquals(1, session.sectionIndex)
        assertEquals(40 * 60_000L, session.remainingMs())
    }

    @Test
    fun drillsAreUntimedAndReplayable() {
        val session = ExamSession(ExamAssembler.jlptTypeDrill(5, blueprint, "task", n5Pool(), passages, Random(1)), blueprint, TestClock())
        assertNull(session.remainingMs())
        val id = session.current!!.item.id
        session.audioPlayed(id)
        assertTrue(session.canPlayAudio(id))
    }

    @Test
    fun strictListeningPlaysOnce() {
        val session = ExamSession(ExamAssembler.jlptMock(5, blueprint, n5Pool(), passages, Random(1)), blueprint, TestClock())
        assertTrue(session.canPlayAudio("x"))
        session.audioPlayed("x")
        assertFalse(session.canPlayAudio("x"))
    }

    @Test
    fun perfectMockScoresAndPasses() {
        val clock = TestClock()
        val session = ExamSession(ExamAssembler.jlptMock(5, blueprint, n5Pool(), passages, Random(1)), blueprint, clock)
        while (!session.finished) {
            val s = session.section!!
            for (i in s.items.indices) {
                session.goTo(i)
                session.choose(1)
                clock.advance(10.seconds)
            }
            session.nextSection()
        }
        val result = session.submit()
        assertEquals(180, result.scoring.total)
        assertEquals(true, result.scoring.passed)
        assertEquals("N5 · 180/180 · pass", result.summary)
        assertTrue(result.missedRefs.isEmpty())
        assertTrue(result.answers.all { it.timeMs > 0 })
    }

    @Test
    fun unansweredCountsWrongAndMissedRefsSurface() {
        val session = ExamSession(ExamAssembler.jlptTypeDrill(5, blueprint, "grammar_form", n5Pool(), passages, Random(1)), blueprint, TestClock())
        session.choose(0)
        val result = session.submit()
        assertEquals(0, result.answers.count { it.correct })
        assertEquals(10, result.missedRefs.size)
        assertEquals(false, result.scoring.passed)
        assertTrue(result.summary.contains("Item-type drill"))
    }

    @Test
    fun dlptSliceSpreadsLevelsEasiestFirst() {
        val levels = listOf("0+", "1", "1+", "2", "2+", "3")
        val pool = levels.flatMap { l -> (1..10).map { item("$l-$it", "main_idea", level = l, exam = ExamKind.DLPT_READING) } }
        val form = ExamAssembler.dlpt(ExamKind.DLPT_READING, 30, pool, emptyMap(), Random(3))
        assertEquals(ExamMode.SLICE_30, form.mode)
        assertEquals(12, form.items.size) // 10 items wanted → 2 per level
        assertEquals(levels, form.items.map { it.item.level }.distinct())
        val session = ExamSession(form, null, TestClock())
        form.items.forEachIndexed { i, f -> session.goTo(i); if (f.item.level in levels.take(4)) session.choose(1) }
        val result = session.submit()
        assertEquals("2", result.scoring.ilrProvisional)
        assertNull(result.scoring.ilr) // 8 items at ≤ 2 is short of the 20-item rule
        assertTrue(result.summary.contains("provisional"))
    }
}
