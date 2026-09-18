package app.tsumugi.exam

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.exam.db.ExamDatabase
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * F-24: exam attempts survive process death and follow the wall clock. "Process death" is a fresh [ExamService]
 * (and so a fresh session) over the same user database.
 */
class ExamResumeTest {

    private val clock = TestClock()
    private val userDriver = inMemoryDriver(TsumugiDatabase.Schema)
    private val db = TsumugiDatabase(userDriver)
    private val pack = ExamDatabase(inMemoryDriver(ExamDatabase.Schema).also { d ->
        d.execute(null, "INSERT INTO pack_meta(key, value) VALUES (?, ?)", 2) {
            bindString(0, ExamService.BLUEPRINT_KEY)
            bindString(1, JlptScoringTest.BLUEPRINT_JSON)
        }
        // Two N5 items for one type in each of the three sections (vocabulary 20 min, grammar 40, listening 30).
        listOf("kanji_reading", "grammar_form", "task").forEach { type ->
            (1..2).forEach { n ->
                d.execute(
                    null,
                    "INSERT INTO exam_item(id, bank, exam, level, type, passage_id, ord, stem, choices, answer, explanation, script, refs, source, verified) " +
                        "VALUES (?, 'test', 'JLPT', 'N5', ?, NULL, ?, 'stem', '[\"a\",\"b\",\"c\",\"d\"]', 1, '', '', '[]', 'human', 1)",
                    3,
                ) {
                    bindString(0, "$type-$n")
                    bindString(1, type)
                    bindLong(2, n.toLong())
                }
            }
        }
    })

    /** A new service over the same databases, as after the process was killed and the app relaunched. */
    private fun service() =
        ExamService(pack, db, "dev", { null }, { null }, CollectionService(db, SrsRepository(db, "dev"), { null }, clock), clock)

    @Test
    fun resumeAfterProcessDeathRestoresAnswersAndCurrentSection() = runTest {
        val first = assertNotNull(service().jlptMock(5, seed = 1))
        assertEquals(3, first.form.sections.size)
        first.choose(2)
        val firstItem = first.current!!.item.id
        first.nextSection()
        first.goTo(1)
        first.choose(3)
        val secondItem = first.current!!.item.id
        clock.advance(5.minutes)

        val relaunched = service()
        val summary = assertNotNull(relaunched.inProgress())
        assertEquals(first.attemptId, summary.id)
        assertEquals(2, summary.answered)
        assertEquals(6, summary.total)
        assertEquals(1, summary.sectionIndex)
        assertFalse(summary.timeUp)

        val resumed = assertNotNull(relaunched.resume())
        assertEquals(first.attemptId, resumed.attemptId)
        assertEquals(first.startedAt, resumed.startedAt)
        assertEquals(1, resumed.sectionIndex)
        assertEquals(1, resumed.index)
        assertEquals(secondItem, resumed.current!!.item.id)
        assertEquals(2, resumed.choiceFor(firstItem))
        assertEquals(3, resumed.choiceFor(secondItem))
        assertEquals(35 * 60_000L, resumed.remainingMs())
        assertFalse(resumed.finished)
    }

    @Test
    fun backgroundingAcrossTwoSectionDeadlinesClosesBoth() = runTest {
        val session = assertNotNull(service().jlptMock(5, seed = 1))
        session.choose(1)
        // Away for 61 minutes: vocabulary (20) and grammar (40, opened at the vocabulary deadline) both ran out.
        clock.advance(61.minutes)

        var closed = 0
        while (session.tick()) closed++
        assertEquals(2, closed)
        assertEquals(2, session.sectionIndex)
        assertEquals(29 * 60_000L, session.remainingMs())

        // The same holds when the process died meanwhile: resume() closes both before returning.
        val other = assertNotNull(service().jlptMock(5, seed = 2))
        other.begin()
        clock.advance(61.minutes)
        val summary = assertNotNull(service().inProgress())
        assertEquals(other.attemptId, summary.id)
        assertEquals(0, summary.sectionIndex)
        val resumed = assertNotNull(service().resume())
        assertEquals(2, resumed.sectionIndex)
        assertEquals(29 * 60_000L, resumed.remainingMs())
        assertEquals(2, service().inProgress()!!.sectionIndex)
    }

    @Test
    fun timeRunningOutOnTheLastSectionFinishesTheAttempt() = runTest {
        assertNotNull(service().jlptMock(5, seed = 1)).begin()
        clock.advance(91.minutes)
        assertTrue(service().inProgress()!!.timeUp)
        val resumed = assertNotNull(service().resume())
        assertTrue(resumed.finished)
        assertEquals(6, resumed.submit().answers.size)
        assertNull(service().inProgress())
    }

    @Test
    fun submittingClearsTheInProgressRow() = runTest {
        val svc = service()
        val session = assertNotNull(svc.jlptMock(5, seed = 1))
        session.choose(1)
        assertNotNull(svc.inProgress())
        svc.save(session.submit())
        assertNull(svc.inProgress())
        assertNull(service().resume())
        assertEquals(1, svc.history().size)
    }

    @Test
    fun discardAndNewAttemptsReplaceTheOldOne() = runTest {
        val svc = service()
        val first = assertNotNull(svc.jlptMock(5, seed = 1))
        first.begin()
        assertEquals(first.attemptId, svc.inProgress()!!.id)
        val drill = assertNotNull(svc.jlptTypeDrill(5, "task", seed = 1))
        // A form built for a preview doesn't replace the unfinished attempt; starting it does.
        assertEquals(first.attemptId, svc.inProgress()!!.id)
        drill.begin()
        assertEquals(drill.attemptId, svc.inProgress()!!.id)
        assertTrue(first.attemptId != drill.attemptId)
        assertNull(svc.inProgress()!!.sectionDeadline)
        svc.discardInProgress()
        assertNull(svc.inProgress())
        assertNull(svc.resume())
    }
}
