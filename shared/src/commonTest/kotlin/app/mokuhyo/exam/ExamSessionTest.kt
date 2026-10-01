package app.mokuhyo.exam

import app.mokuhyo.testing.TestClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ExamSessionTest {
    private val levels = listOf("0+", "1", "1+", "2", "2+", "3")

    private fun item(id: String, level: String, passage: String? = null, exam: ExamKind = ExamKind.DLPT_READING, verified: Boolean = false) =
        ExamItem(id, "test", exam, level, "main_idea", passage, "stem $id", listOf("a", "b", "c", "d"), 1, "", emptyList(), listOf("v:$id"), "llm", verified)

    private fun pool(exam: ExamKind = ExamKind.DLPT_READING) = levels.flatMap { l -> (1..10).map { item("$l-$it", l, exam = exam) } }

    @Test
    fun passageGroupsStayWhole() {
        val grouped = (1..3).map { item("p$it", "2", passage = "p") } + (1..5).map { item("s$it", "2") }
        repeat(20) { seed ->
            val picked = ExamAssembler.pick(grouped, 4, Random(seed))
            val fromPassage = picked.count { it.passageId == "p" }
            assertTrue(fromPassage == 0 || fromPassage == 3, "passage split: $fromPassage")
        }
    }

    @Test
    fun verifiedItemsArePreferred() {
        val candidates = (1..5).map { item("u$it", "2") } + (1..2).map { item("v$it", "2", verified = true) }
        repeat(10) { seed ->
            val picked = ExamAssembler.pick(candidates, 2, Random(seed))
            assertTrue(picked.all { it.verified })
        }
    }

    @Test
    fun strictSectionsCloseWhenTimeRunsOut() {
        val clock = TestClock()
        val session = ExamSession(ExamAssembler.dlpt(ExamKind.DLPT_READING, 30, pool(), emptyMap(), Random(1)), clock)
        assertEquals(30 * 60_000L, session.remainingMs())
        clock.advance(29.minutes)
        assertFalse(session.tick())
        clock.advance(2.minutes)
        assertTrue(session.tick())
        assertTrue(session.finished)
    }

    @Test
    fun strictListeningPlaysOnce() {
        val session = ExamSession(ExamAssembler.dlpt(ExamKind.DLPT_LISTENING, 30, pool(ExamKind.DLPT_LISTENING), emptyMap(), Random(1)), TestClock())
        val id = session.current!!.item.id
        assertTrue(session.canPlayAudio(id))
        session.audioPlayed(id)
        assertFalse(session.canPlayAudio(id))
    }

    @Test
    fun unansweredCountsWrongAndMissedRefsSurface() {
        val session = ExamSession(ExamAssembler.dlpt(ExamKind.DLPT_READING, 30, pool(), emptyMap(), Random(1)), TestClock())
        session.choose(1)
        val result = session.submit()
        assertEquals(1, result.answers.count { it.correct })
        assertEquals(result.answers.size - 1, result.missedRefs.size)
    }

    @Test
    fun dlptSliceSpreadsLevelsEasiestFirst() {
        val form = ExamAssembler.dlpt(ExamKind.DLPT_READING, 30, pool(), emptyMap(), Random(3))
        assertEquals(ExamMode.SLICE_30, form.mode)
        assertEquals(12, form.items.size) // 10 items wanted → 2 per level
        assertEquals(levels, form.items.map { it.item.level }.distinct())
        val session = ExamSession(form, TestClock())
        form.items.forEachIndexed { i, f ->
            session.goTo(i)
            if (f.item.level in levels.take(4)) session.choose(1)
        }
        val result = session.submit()
        assertEquals("2", result.scoring.ilrProvisional)
        assertNull(result.scoring.ilr) // 8 items at ≤ 2 is short of the 20-item rule
        assertTrue(result.summary.contains("provisional"))
    }
}
