package app.tsumugi.courses

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.exam.ExamService
import app.tsumugi.exam.db.ExamDatabase
import app.tsumugi.grammar.GrammarService
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.onomatopoeia.OnomatopoeiaRepository
import app.tsumugi.path.db.PathDatabase
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathProgressStore
import app.tsumugi.srs.PathService
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs against the real content/packs when built (tools/packs/build_all.py); skipped otherwise. Checks the Phase 12
 * content the course, monolingual and onomatopoeia features promise (BRIEF_V2 §6.6, §6.8).
 */
class RealPhase12PackTest {
    private fun pack(name: String) = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "content/packs/$name") }
        .firstOrNull { it.exists() }

    private fun readOnly(file: File) = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("open_mode", "1") })

    @Test
    fun coursesBuildForEveryLevelFromTheRealPacks() = runTest {
        val pathFile = pack("kanji-path.sqlite") ?: return@runTest println("RealPhase12PackTest skipped: no path pack")
        val grammarFile = pack("grammar.sqlite") ?: return@runTest println("RealPhase12PackTest skipped: no grammar pack")
        val examFile = pack("exam.sqlite") ?: return@runTest println("RealPhase12PackTest skipped: no exam pack")
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val srs = SrsRepository(db, "dev")
        val settings = SettingsRepository(db)
        val path = PathService(PathDatabase(readOnly(pathFile)), srs, settings, PathProgressStore(db))
        val grammar = GrammarService(GrammarDatabase(readOnly(grammarFile)), srs) { null }
        val exams = ExamService(ExamDatabase(readOnly(examFile)), db, "dev", { grammar }, { null }, CollectionService(db, srs, { path }))
        val mastery = GrammarMasteryStore(db)
        val courses = CourseService(settings, mastery, LearnerKnowledge(db, srs), { path }, { grammar }, { exams })

        val overview = courses.overview()
        assertEquals(listOf(5, 4, 3, 2, 1), overview.map { it.level })
        for (level in 1..5) {
            val course = courses.course(level)
            println(
                "RealPhase12PackTest N$level: ${course.modules.size} modules, ${course.progress.kanjiTotal} kanji, " +
                    "${course.progress.wordsTotal} words, ${course.progress.grammarTotal} grammar, ${course.sections.size} sections",
            )
            assertTrue(course.modules.isNotEmpty() && course.progress.grammarTotal > 0 && course.sections.isNotEmpty())
            assertTrue(course.modules.all { m -> m.quiz.isNotEmpty() && m.mock != null })
            assertEquals(0, course.progress.percent)
        }
        val n5 = courses.course(5)
        val first = n5.modules.first().grammar.first()
        courses.setMastered(first.pointId, true)
        val after = courses.remaining(5)
        assertTrue(after.grammar.none { it.pointId == first.pointId })
        assertEquals(n5.progress.grammarTotal - 1, after.grammar.size)
        assertTrue(srs.stagesFor(listOf("g:${first.pointId}")).isEmpty(), "mastery never creates SRS cards")
    }

    @Test
    fun everyN2AndN1GrammarPointHasAJapaneseExplanation() = runTest {
        val file = pack("grammar.sqlite") ?: return@runTest println("RealPhase12PackTest skipped: no grammar pack")
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val grammar = GrammarService(GrammarDatabase(readOnly(file)), SrsRepository(db, "dev")) { null }
        val counts = (1..5).associateWith { level -> grammar.points(level).size to grammar.japaneseIds(level).size }
        println("RealPhase12PackTest grammar ja: $counts")
        if (counts.values.sumOf { it.second } == 0) return@runTest println("RealPhase12PackTest skipped: grammar pack predates grammar_point_ja")
        for (level in 1..2) assertEquals(counts.getValue(level).first, counts.getValue(level).second, "N$level")
        val sai = grammar.japanese("n2-sai")
        assertTrue(sai != null && sai.meaning.isNotBlank() && sai.meaning.none { it in 'A'..'z' })
    }

    @Test
    fun onomatopoeiaModuleHasSixHundredDescribedWordsAndAQuiz() = runTest {
        val file = pack("dictionary.sqlite") ?: return@runTest println("RealPhase12PackTest skipped: no dictionary pack")
        val repo = OnomatopoeiaRepository(DictionaryDatabase(readOnly(file)))
        if (!repo.available()) return@runTest println("RealPhase12PackTest skipped: dictionary pack predates the onomatopoeia tables")
        val all = repo.all()
        val themes = repo.themes()
        println("RealPhase12PackTest onomatopoeia: ${all.size} words, ${all.count { it.hasFeel }} with feel, " + themes.joinToString { "${it.id}=${it.count}" })
        assertTrue(all.count { it.hasFeel } >= 600)
        assertTrue(all.take(600).all { it.hasFeel }, "the most frequent 600 all have a feel line")
        assertEquals(themes.size, themes.map { it.svg }.distinct().size, "one glyph per theme")
        assertTrue(themes.all { it.svg.startsWith("<svg") && it.count > 0 })
        val quiz = repo.quiz(count = 30, seed = 42)
        assertEquals(30, quiz.size)
        assertTrue(quiz.all { q -> q.options.filter { it != q.target }.none { app.tsumugi.onomatopoeia.OnomatopoeiaQuiz.confusable(q.target, it) } })
        val withExamples = all.firstOrNull { it.exampleIds.isNotEmpty() }
        if (withExamples != null) assertTrue(repo.examples(withExamples).isNotEmpty())
    }
}
