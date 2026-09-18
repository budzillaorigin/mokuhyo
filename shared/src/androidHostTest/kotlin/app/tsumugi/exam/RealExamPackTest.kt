package app.tsumugi.exam

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.exam.db.ExamDatabase
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.opi.OpiSession
import app.tsumugi.ai.AiGateway
import app.tsumugi.practice.PracticeRepository
import app.tsumugi.practice.db.PracticeDatabase
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.study.activities.PomodoroSession
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Properties
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs against the real content/packs/exam.sqlite and practice.sqlite when built (tools/packs/build_all.py);
 * skipped otherwise. Checks the packs load with the app's schema and that forms and interviews assemble.
 */
class RealExamPackTest {

    private fun pack(name: String) = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "content/packs/$name") }
        .firstOrNull { it.exists() }

    private fun readOnly(file: File) = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("open_mode", "1") })

    @Test
    fun examPackLoads() = runTest {
        val file = pack("exam.sqlite") ?: return@runTest println("RealExamPackTest skipped: no exam pack")
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val srs = SrsRepository(db, "dev")
        val service = ExamService(ExamDatabase(readOnly(file)), db, "dev", { null }, { null }, CollectionService(db, srs, { null }))
        val blueprints = assertNotNull(service.blueprints())
        assertEquals(5, blueprints.levels.size)
        val coverage = service.coverage()
        println("RealExamPackTest coverage: " + coverage.joinToString { "${it.exam}/${it.level}=${it.total}" })
        for (level in 1..5) {
            val session = assertNotNull(service.jlptMock(level, seed = 1))
            if (coverage.any { it.exam == ExamKind.JLPT && it.level == "N$level" }) assertTrue(session.totalCount > 0, "N$level mock is empty")
        }
        if (coverage.any { it.exam == ExamKind.DLPT_READING }) {
            val dlpt = service.dlpt(ExamKind.DLPT_READING, 60, seed = 1)
            assertTrue(dlpt.totalCount >= 10)
            assertTrue(dlpt.form.items.all { f -> f.item.passageId == null || f.item.passageId in dlpt.form.passages })
        }
    }

    @Test
    fun practicePackLoads() = runTest {
        val file = pack("practice.sqlite") ?: return@runTest println("RealExamPackTest skipped: no practice pack")
        val practice = PracticeRepository(PracticeDatabase(readOnly(file)))
        assertTrue(practice.scenarios().size >= 20)
        val banks = IlrLevel.lowerRange.associateWith { practice.opiBank(it.label) }.filterValues { it.questions.isNotEmpty() }
        val session = OpiSession(banks, AiGateway({ null }), random = Random(1))
        var turns = 0
        while (session.next() != null) {
            session.answer("はい、そうですね。")
            turns++
        }
        assertEquals(OpiSession.PLAN.values.sum(), turns)
        val pomodoro = assertNotNull(PomodoroSession.build(practice, 4, Random(1)))
        assertTrue(pomodoro.activities.size >= 8, "queue ${pomodoro.activities.size}")
    }
}
