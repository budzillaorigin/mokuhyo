package app.tsumugi.translation

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.FakeModel
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.linguist.db.LinguistDatabase
import app.tsumugi.srs.SrsRepository
import app.tsumugi.sync.FakeSyncServer
import app.tsumugi.sync.SyncEngine
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** BRIEF_V2 §6.12: diff, sight timer, grading with and without a model, the skill line and its sync (D-270…D-274). */
class TranslationWorkbenchTest {
    private val clock = TestClock()

    // The pack's rows, written with raw SQL like the builder writes them (pack schemas have no insert queries).
    private val linguistDriver = inMemoryDriver(LinguistDatabase.Schema).also { d ->
        fun insert(id: String, ord: Int, dir: String, genre: String, level: String, text: String, ref: String, kp: String, source: String) =
            d.execute(
                null,
                "INSERT INTO translation_passage VALUES ('$id', $ord, '$dir', '$genre', '$level', 3, '2', 'T', '$text', " +
                    "'${ref.replace("'", "''")}', 'neutral news English', '$kp', 'notes', 'original', NULL, 60, '$source', 0)",
                0,
            )
        insert(
            "tr-news-001", 0, "JE", "news", "N3", "市は来月から、深夜バスの運行を週末にも拡大すると発表した。",
            "The city announced that, from next month, it will expand its late-night bus service to weekends as well.",
            """["from next month","late-night bus service","weekends as well"]""", "llm",
        )
        insert("tatoeba-legal-01", 1, "EJ", "legal", "N4", "The law protects everyone.", "法律はみんなを守る。", "[]", "tatoeba")
    }
    private val repo = TranslationRepository(LinguistDatabase(linguistDriver))

    private fun service(db: TsumugiDatabase, model: FakeModel?, device: String = "dev"): TranslationService {
        return TranslationService(db, { repo }, { AiGateway({ model }) }, device, clock, { TimeZone.UTC })
    }

    private val good = """{"accuracy":4,"completeness":3,"register":4,"naturalness":4,"feedback":"Accurate and natural news English. You dropped 'as well' (にも), which tells readers weekday service already exists.","issues":[{"kind":"omission","attempt_span":"","reference_span":"as well","note":"にも means the weekends are added to existing service."}],"better":""}"""

    @Test
    fun englishDiffIgnoresCaseAndPunctuation() {
        val d = TranslationDiff.of("The city will extend buses to weekends.", "The city will expand its bus service to weekends as well.")
        assertEquals(DiffKind.SAME, d.segments.first().kind)
        assertTrue(d.segments.any { it.kind == DiffKind.MISSING && "as well." in it.text }, d.segments.toString())
        assertTrue(d.segments.any { it.kind == DiffKind.EXTRA && "extend" in it.text })
        assertEquals(11, d.referenceUnits)
        assertEquals(5, d.matched) // the city will … to weekends (case and the final period don't matter)
    }

    @Test
    fun japaneseDiffIsByCharacterAndFoldsKatakana() {
        val d = TranslationDiff.of("バスは週末も走る", "ばすは週末にも走ります。")
        assertEquals(11, d.referenceUnits, "punctuation isn't a unit")
        assertEquals(7, d.matched) // ばすは週末 + も + 走
        assertTrue(d.segments.any { it.kind == DiffKind.MISSING && it.text == "に" })
        assertTrue(d.overlap in 0.63..0.64)
    }

    @Test
    fun sightTimerMatchesTheBuilder() {
        assertEquals(100, SightTimer.seconds("あ".repeat(200), TranslationDirection.JE))
        assertEquals(140, SightTimer.seconds(List(100) { "word" }.joinToString(" "), TranslationDirection.EJ))
        assertEquals(30, SightTimer.seconds("短い。", TranslationDirection.JE))
        assertEquals(300, SightTimer.seconds("あ".repeat(2000), TranslationDirection.JE))
    }

    @Test
    fun importedTextHasNoReferenceAndItsDirectionFromTheScript() {
        val ja = ImportedPassage.of("今日はいい天気ですね。")
        assertEquals(TranslationDirection.JE, ja.direction)
        assertTrue(ja.id.startsWith("user:") && !ja.hasReference && ja.origin == PassageOrigin.USER)
        assertEquals(TranslationDirection.EJ, ImportedPassage.of("It is sunny today.").direction)
        assertEquals(ja.id, ImportedPassage.of("  今日はいい天気ですね。 ").id, "the id is a key of the text")
    }

    @Test
    fun aModelGradeIsSavedLabeledWithTheDiff() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val workbench = service(db, FakeModel(good))
        val passage = assertNotNull(workbench.passage("tr-news-001"))
        assertTrue(passage.isAiGenerated)
        assertEquals(3, passage.keyPoints.size)
        val r = workbench.grade(passage, "The city announced it will extend late-night buses to weekends starting next month.", TranslationMode.SIGHT, 70_000)
        assertIs<TranslationGradeResult.Graded>(r)
        assertEquals(94, r.attempt.score)
        assertTrue(r.attempt.isAiGraded && r.attempt.grade?.issues?.single()?.kind == "omission")
        assertEquals(60_000L, r.attempt.timeLimitMs)
        assertTrue(r.attempt.overTime)
        assertNotNull(r.diff)
        assertEquals(1, workbench.history().size)
        assertEquals(1, workbench.history("tr-news-001").size)
        assertTrue(workbench.available())
        assertEquals(listOf("tatoeba-legal-01"), workbench.passages(genre = "legal").map { it.id })
        assertTrue(!workbench.passages(direction = TranslationDirection.EJ).first().isAiGenerated, "Tatoeba pairs carry no badge")
    }

    @Test
    fun withoutAModelTheReferenceAndRubricStandInAndNothingIsSavedUntilSelfAssessed() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val workbench = service(db, null)
        val passage = workbench.passage("tr-news-001")!!
        val r = workbench.grade(passage, "The city will expand bus service.", TranslationMode.WRITTEN, 30_000)
        assertIs<TranslationGradeResult.Unavailable>(r)
        assertNotNull(r.diff)
        assertTrue(workbench.history().isEmpty())
        val a = workbench.selfAssess(passage, "The city will expand bus service.", TranslationMode.WRITTEN, 30_000, 3, 2, 4, 5)
        assertEquals(TranslationAttempt.GRADER_SELF, a.grader)
        assertEquals(4, a.naturalness, "scores are clamped to 0–4")
        assertEquals(81, a.score) // 13/16
        assertNull(a.timeLimitMs, "written mode has no limit")
        assertEquals(4, TranslationRubric.criteria.size)
        assertIs<TranslationGradeResult.Unavailable>(workbench.grade(passage, "  ", TranslationMode.WRITTEN, 0))
    }

    @Test
    fun theSkillLineAveragesByDayDirectionAndGenreWithATrend() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val workbench = service(db, null)
        val news = workbench.passage("tr-news-001")!!
        val legal = workbench.passage("tatoeba-legal-01")!!
        repeat(5) { workbench.selfAssess(news, "x", TranslationMode.WRITTEN, 1, 2, 2, 2, 2); clock.advance(1.days) } // 50 each
        repeat(5) { workbench.selfAssess(legal, "x", TranslationMode.WRITTEN, 1, 4, 4, 4, 4); clock.advance(1.days) } // 100 each
        val line = workbench.skillLine()
        assertEquals(10, line.attempts)
        assertEquals(10, line.daily.size)
        assertEquals(50, line.recentByGenre["news"])
        assertEquals(100, line.recentByDirection[TranslationDirection.EJ])
        assertEquals(50, line.trend)
        assertEquals("2026-09-01", line.points.first().day)
        workbench.delete(workbench.history().first().id)
        assertEquals(9, workbench.skillLine().attempts)
    }

    @Test
    fun attemptsSyncAndADeleteReachesTheOtherDevice() = runTest {
        val server = FakeSyncServer()
        class Device(id: String) {
            val driver = inMemoryDriver(TsumugiDatabase.Schema)
            val db = TsumugiDatabase(driver)
            val engine = SyncEngine(driver, db, SrsRepository(db, id, clock), id, server, null, clock)
            val workbench = service(db, null, id)
        }
        val a = Device("device-a")
        val b = Device("device-b")
        val passage = a.workbench.passage("tr-news-001")!!
        val saved = a.workbench.selfAssess(passage, "The city…", TranslationMode.WRITTEN, 1, 3, 3, 3, 3)
        a.engine.sync(); b.engine.sync()
        assertEquals(listOf(saved.id), b.workbench.history().map { it.id })
        assertEquals("The city…", b.workbench.history().single().attemptText)
        clock.advance(5.seconds)
        b.workbench.delete(saved.id)
        b.engine.sync(); a.engine.sync()
        assertTrue(a.workbench.history().isEmpty())
    }
}
