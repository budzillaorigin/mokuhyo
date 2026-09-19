package app.tsumugi.exam

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.exam.dlpt.DlptRange
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.CollectionService
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** ILR 3+/4 upper-range forms (BRIEF_V2 G-08) and the DLPT text-type filter (§6.16). */
class DlptUpperRangeTest {

    private val allLevels = listOf("0+", "1", "1+", "2", "2+", "3", "3+", "4")
    private val types = listOf("news", "editorial", "liaison")

    /** Per level and text type: two passages with three items each. */
    private val passages: Map<String, ExamPassage> = allLevels.flatMap { l -> types.flatMap { t -> (1..2).map { n -> "$l-$t-$n" to t } } }
        .associate { (id, t) -> id to ExamPassage(id, ExamKind.DLPT_READING, id.substringBefore('-'), t, id, "本文", emptyList(), "llm", false) }

    private val pool: List<ExamItem> = passages.values.flatMap { p ->
        (1..3).map { q ->
            ExamItem("${p.id}-q$q", "test", ExamKind.DLPT_READING, p.level, "main_idea", p.id, "stem", listOf("a", "b", "c", "d"), 0, "", emptyList(), emptyList(), "llm", false)
        }
    }

    @Test
    fun ilrLevelsParseAndOrderIncludingTheUpperRange() {
        assertEquals(IlrLevel.L3_PLUS, IlrLevel.parse("3+"))
        assertEquals(IlrLevel.L4, IlrLevel.parse("4"))
        assertEquals(listOf(IlrLevel.L3, IlrLevel.L3_PLUS, IlrLevel.L4), DlptRange.UPPER.levels)
        assertEquals(allLevels, IlrLevel.tested.map { it.label })
        assertTrue(IlrLevel.L4 > IlrLevel.L3_PLUS && IlrLevel.L3_PLUS > IlrLevel.L3)
    }

    @Test
    fun lowerRangeFormsNeverIncludeUpperRangeItems() {
        val form = ExamAssembler.dlpt(ExamKind.DLPT_READING, 180, pool, passages, Random(1))
        val levels = form.items.map { it.item.level }.distinct()
        assertEquals(IlrLevel.lowerRange.map { it.label }, levels)
        assertEquals("", form.level)
    }

    @Test
    fun upperRangeFormsSpreadOverThreeToFourEasiestFirst() {
        val form = ExamAssembler.dlpt(ExamKind.DLPT_READING, 60, pool, passages, Random(2), DlptRange.UPPER)
        assertEquals(listOf("3", "3+", "4"), form.items.map { it.item.level }.distinct())
        assertEquals("UPPER", form.level)
        assertEquals(DlptRange.UPPER, DlptRange.ofFormLevel(form.level))
        assertTrue(form.items.size >= 15)
        assertTrue(form.items.all { it.item.passageId in form.passages })
        assertEquals(setOf("ILR 3", "ILR 3+", "ILR 4"), form.items.map { it.typeTitle }.toSet())
    }

    @Test
    fun textTypeFilterKeepsOnlyThoseTypesAndReportsShortfalls() {
        val form = ExamAssembler.dlpt(ExamKind.DLPT_READING, 60, pool, passages, Random(3), textTypes = setOf("liaison"))
        assertTrue(form.items.isNotEmpty())
        assertTrue(form.items.all { form.passages.getValue(it.item.passageId!!).textType == "liaison" })
        val long = ExamAssembler.dlpt(ExamKind.DLPT_READING, 180, pool, passages, Random(3), textTypes = setOf("liaison"))
        assertTrue(long.shortfalls.isNotEmpty(), "10 wanted per level, 6 liaison items per level")
        val none = ExamAssembler.dlpt(ExamKind.DLPT_READING, 60, pool, passages, Random(3), textTypes = setOf("poetry"))
        assertTrue(none.isEmpty)
    }

    @Test
    fun textTypeCountsListTheFilterOptions() {
        val lower = pool.filter { IlrLevel.parse(it.level) in IlrLevel.lowerRange }
        val counts = ExamAssembler.textTypeCounts(lower, passages)
        assertEquals(types.sorted(), counts.map { it.first })
        assertTrue(counts.all { it.second == 6 * IlrLevel.lowerRange.size })
        assertEquals(pool, ExamAssembler.filterByTextType(pool, passages, emptySet()))
    }

    @Test
    fun upperRangeSummaryFallsBackToBelowIlr3() {
        val form = ExamAssembler.dlpt(ExamKind.DLPT_READING, 30, pool, passages, Random(4), DlptRange.UPPER)
        val session = ExamSession(form, null, TestClock())
        form.items.forEachIndexed { i, _ -> session.goTo(i); session.choose(3) } // every answer wrong
        val result = session.submit()
        assertTrue(result.summary.contains("below ILR 3"), result.summary)
    }

    @Test
    fun serviceBuildsUpperRangeFormsFromImportedBanks() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val clock = TestClock()
        val service = ExamService(null, db, "dev", { null }, { null }, CollectionService(db, SrsRepository(db, "dev"), { null }, clock), clock)
        val bank = """
        {"bank":"upper","title":"Upper","passages":[
          {"id":"u1","exam":"DLPT_READING","level":"3+","textType":"editorial","title":"A","body":"本文"},
          {"id":"u2","exam":"DLPT_READING","level":"4","textType":"literary","title":"B","body":"本文"},
          {"id":"l1","exam":"DLPT_READING","level":"2","textType":"news","title":"C","body":"本文"}],
         "items":[
          {"id":"u1-q1","exam":"DLPT_READING","level":"3+","type":"tone","passageId":"u1","stem":"s","choices":["a","b","c","d"],"answer":0},
          {"id":"u2-q1","exam":"DLPT_READING","level":"4","type":"inference","passageId":"u2","stem":"s","choices":["a","b","c","d"],"answer":1},
          {"id":"l1-q1","exam":"DLPT_READING","level":"2","type":"detail","passageId":"l1","stem":"s","choices":["a","b","c","d"],"answer":2}]}
        """.trimIndent()
        assertIs<BankImportResult.Imported>(service.importBank(bank))
        val upper = service.dlpt(ExamKind.DLPT_READING, 30, seed = 1, range = DlptRange.UPPER)
        assertEquals(setOf("u1-q1", "u2-q1"), upper.form.items.map { it.item.id }.toSet())
        val literary = service.dlpt(ExamKind.DLPT_READING, 30, seed = 1, range = DlptRange.UPPER, textTypes = setOf("literary"))
        assertEquals(listOf("u2-q1"), literary.form.items.map { it.item.id })
        assertEquals(listOf("editorial" to 1, "literary" to 1), service.dlptTextTypes(ExamKind.DLPT_READING, DlptRange.UPPER))
        assertEquals(listOf("news" to 1), service.dlptTextTypes(ExamKind.DLPT_READING))
    }
}
