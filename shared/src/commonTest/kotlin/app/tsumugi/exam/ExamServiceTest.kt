package app.tsumugi.exam

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExamServiceTest {

    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val clock = TestClock()
    private val srs = SrsRepository(db, "dev")
    private val service = ExamService(null, db, "dev", { null }, { null }, CollectionService(db, srs, { null }, clock), clock)

    private val bank = """
    {"bank":"mine","title":"My DLPT set","passages":[
      {"id":"p1","exam":"DLPT_READING","level":"1","textType":"sign","title":"No parking","body":"駐車禁止"}],
     "items":[
      {"id":"i1","exam":"DLPT_READING","level":"1","type":"main_idea","passageId":"p1","stem":"What does the sign say?",
       "choices":["No parking","Exit","Open 24 hours","Closed today"],"answer":0,"explanation":"駐車 = parking, 禁止 = prohibited."},
      {"id":"i2","exam":"DLPT_READING","level":"2","type":"detail","stem":"Q2","choices":["a","b","c","d"],"answer":3}]}
    """.trimIndent()

    @Test
    fun importRunSaveAndReview() = runTest {
        val imported = service.importBank(bank)
        assertIs<BankImportResult.Imported>(imported)
        assertEquals("user:mine", imported.bank)
        assertEquals(2, service.coverage().sumOf { it.total })

        val session = service.dlpt(ExamKind.DLPT_READING, 30)
        assertEquals(2, session.totalCount)
        assertNotNull(session.passage ?: session.form.passages["p1"])
        session.form.items.forEachIndexed { i, f -> session.goTo(i); session.choose(f.item.answer) }
        val id = service.save(session.submit())

        val history = service.history(ExamKind.DLPT_READING)
        assertEquals(listOf(id), history.map { it.id })
        val review = assertNotNull(service.attempt(id))
        assertEquals(2, review.items.size)
        assertTrue(review.items.all { it.answer.correct })
        assertEquals(setOf("p1"), review.passages.keys)
    }

    @Test
    fun invalidBanksListEveryProblem() = runTest {
        val bad = """{"bank":"x","items":[{"id":"a","exam":"DLPT_READING","level":"9","type":"detail","stem":"s","choices":["a","a"],"answer":5,"passageId":"nope"}]}"""
        val result = service.importBank(bad)
        assertIs<BankImportResult.Invalid>(result)
        assertTrue(result.errors.size >= 4, result.errors.toString())
        assertIs<BankImportResult.Invalid>(service.importBank("not json"))
    }

    @Test
    fun jlptNeedsTheBlueprintPack() = runTest {
        assertEquals(null, service.jlptMock(3))
    }
}
