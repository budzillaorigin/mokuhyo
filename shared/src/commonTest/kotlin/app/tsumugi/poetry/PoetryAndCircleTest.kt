package app.tsumugi.poetry

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.Token
import app.tsumugi.linguist.db.LinguistDatabase
import app.tsumugi.reader.ReaderAnalyzer
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.RecordingStore
import app.tsumugi.review.LinguistReviewSource
import app.tsumugi.review.ReviewKind
import app.tsumugi.srs.SrsRepository
import app.tsumugi.sync.FakeSyncServer
import app.tsumugi.sync.SyncEngine
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** BRIEF_V2 §6.14: the poetry corner from the pack, the solo reading circle's session model and sync (D-276…D-278). */
class PoetryAndCircleTest {
    private val clock = TestClock()

    private val circleBody = "蜘蛛が一匹いた。男はそれを助けた。\n糸が下りてきた。"

    private val packDriver: SqlDriver = inMemoryDriver(LinguistDatabase.Schema).also { d ->
        fun x(sql: String) = d.execute(null, sql, 0)
        x("INSERT INTO aozora_work VALUES ('000092', '蜘蛛の糸', '芥川竜之介', 'あくたがわ りゅうのすけ', 'Akutagawa Ryūnosuke', '1892-03-01', '1927-07-24', '新字新仮名', 'https://www.aozora.gr.jp/cards/000879/card92.html', '底本：「蜘蛛の糸・杜子春」新潮文庫')")
        x("INSERT INTO poem_theme VALUES ('moon', 0, '月', 'Moon')")
        x("INSERT INTO poem_theme VALUES ('sea', 1, '海', 'Sea')")
        x("INSERT INTO poem VALUES ('nakahara-tsukiyo', 0, '000092', '月夜の浜辺', 'A Moonlit Beach', '中原中也', '月夜の晩に、ボタンが一つ\n波打際に、落ちてゐた。', '[{\"start\":13,\"base\":\"波打際\",\"reading\":\"なみうちぎわ\"}]', '[{\"word\":\"波打際\",\"reading\":\"なみうちぎわ\",\"gloss\":\"water''s edge\",\"start\":13}]', '月の夜にボタンが一つ落ちていた。', 'On a moonlit night a button lay at the water''s edge.', 'Historical kana: ゐた = いた.', 'llm', 0)")
        x("INSERT INTO poem_theme_member VALUES ('moon', 'nakahara-tsukiyo')")
        x("INSERT INTO poem_theme_member VALUES ('sea', 'nakahara-tsukiyo')")
        x("INSERT INTO circle_text VALUES ('akutagawa-kumo-no-ito', 0, '000092', '蜘蛛の糸', 'The Spider''s Thread', '芥川竜之介', 'N2', '$circleBody', '[]', 'A robber and a spider.', 3, 'llm')")
        x("INSERT INTO circle_sentence VALUES ('akutagawa-kumo-no-ito', 0, 0, 8, '蜘蛛が一匹いた。')")
        x("INSERT INTO circle_sentence VALUES ('akutagawa-kumo-no-ito', 1, 8, 17, '男はそれを助けた。')")
        x("INSERT INTO circle_sentence VALUES ('akutagawa-kumo-no-ito', 2, 18, 26, '糸が下りてきた。')")
    }
    private val poetry = PoetryRepository(LinguistDatabase(packDriver))

    private val analyzer = ReaderAnalyzer(
        tokenize = { s -> if (s == "男はそれを助けた。") listOf(Token("男", 0, 1, 1L, "男", "おとこ"), Token("助けた", 5, 8, 2L, "助ける", "たすける", listOf("past"))) else emptyList() },
        summaries = { emptyList() },
        stages = { emptyMap() },
        grammarPatterns = { listOf("n4-ta" to Regex("た。$")) },
    )

    private fun circle(db: TsumugiDatabase, fs: FakeFileSystem, device: String = "dev") = ReadingCircle(
        db, { poetry }, RecordingStore(db, fs, "/data".toPath(), device, clock), { analyzer },
        libraryDocument = { id -> if (id == "d1") "私の本" to ("一文目。二文目！\n三文目" to emptyList()) else null },
        grammarTitle = { if (it == "n4-ta") "〜た (past)" else null }, clock = clock,
    )

    @Test
    fun poemsComeByThemeWithTheirAozoraSourceAndLabels() = runTest {
        assertTrue(poetry.available())
        assertEquals(listOf("moon", "sea"), poetry.themes().map { it.id })
        val list = poetry.poems("sea")
        assertEquals("月夜の晩に、ボタンが一つ", list.single().firstLine)
        assertEquals(listOf("moon", "sea"), list.single().themes)
        val p = assertNotNull(poetry.poem("nakahara-tsukiyo"))
        assertTrue(p.isAiGenerated, "the paraphrase and gloss are LLM-drafted")
        assertEquals("波打際", p.body.substring(p.ruby.single().start, p.ruby.single().start + 3))
        assertEquals(13, p.vocabulary.single().start)
        assertEquals("1927-07-24", p.work?.died)
        assertTrue(p.work!!.colophon.startsWith("底本"))
        assertNull(poetry.poem("nope"))
        assertTrue(PoetryRepository(LinguistDatabase(inMemoryDriver(LinguistDatabase.Schema))).poems().isEmpty())
    }

    @Test
    fun aSessionGoesSentenceBySentenceKeepingItsRecordings() = runTest {
        val fs = FakeFileSystem()
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val c = circle(db, fs)
        assertEquals(listOf("akutagawa-kumo-no-ito"), c.texts().map { it.id })
        var s = assertNotNull(c.start("akutagawa-kumo-no-ito"))
        assertEquals(3, s.sentenceCount)
        assertEquals(s.id, c.start("akutagawa-kumo-no-ito")!!.id, "an unfinished session resumes")

        val pending = c.startRecording()
        fs.write(pending.path.toPath()) { write(ByteArray(32)) }
        s = c.attachReading(s, 0, pending, 2_000)
        assertEquals(s, c.complete(s, 0), "not done until it's explained too")
        s = c.explain(s, 0, "  There was a spider.  ")
        s = c.complete(s, 0)
        assertEquals(1, s.position)
        assertTrue(s.entry(0).done && s.entry(0).explain == "There was a spider.")
        val recs = c.recordingsOf(s, 0)
        assertEquals(RecordingKind.FREE, recs.single().kind)
        assertEquals("circle:${s.id}/0/read", recs.single().ref)

        // A spoken explanation counts as an explanation.
        for (i in 1..2) {
            val read = c.startRecording().also { fs.write(it.path.toPath()) { write(ByteArray(8)) } }
            val said = c.startRecording().also { fs.write(it.path.toPath()) { write(ByteArray(8)) } }
            s = c.attachReading(s, i, read, 1_000)
            s = c.attachExplanationRecording(s, i, said, 1_500)
            s = c.complete(s, i)
        }
        assertTrue(s.finished)
        assertEquals(3, s.doneCount)
        assertEquals(s, c.session(s.id))
        assertFailsWith<IllegalArgumentException> { s.withExplanation(3, "x", 0) }
        c.delete(s)
        assertTrue(c.sessions().isEmpty())
    }

    @Test
    fun eachSentenceGetsDictionaryAndGrammarHelp() = runTest {
        val c = circle(TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema)), FakeFileSystem())
        val reading = assertNotNull(c.reading("akutagawa-kumo-no-ito"))
        val help = assertNotNull(c.help(reading, 1))
        assertEquals("男はそれを助けた。", help.sentence.text)
        assertEquals(listOf("助ける"), help.sentence.tokens.filter { it.surface == "助けた" }.map { it.dictionaryForm })
        assertEquals(13, help.sentence.tokens.first { it.surface == "助けた" }.start, "offsets are in the body")
        assertEquals(listOf("n4-ta" to "〜た (past)"), help.grammar)
        assertNull(c.help(reading, 9))
    }

    @Test
    fun aLibraryDocumentCanBeReadInTheCircle() = runTest {
        val c = circle(TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema)), FakeFileSystem())
        val reading = assertNotNull(c.reading("doc:d1"))
        assertEquals(listOf("一文目。", "二文目！", "三文目"), reading.sentences.map { it.text })
        assertEquals("私の本", assertNotNull(c.start("doc:d1")).title)
        assertNull(c.start("doc:missing"))
        assertEquals(ReadingCircle.splitSentences(circleBody).map { it.start to it.end }, listOf(0 to 8, 8 to 17, 18 to 26), "the pack's split is the reader's")
    }

    @Test
    fun sessionsSyncLastWriterWins() = runTest {
        val server = FakeSyncServer()
        class Device(id: String) {
            val driver = inMemoryDriver(TsumugiDatabase.Schema)
            val db = TsumugiDatabase(driver)
            val engine = SyncEngine(driver, db, SrsRepository(db, id, clock), id, server, null, clock)
            val circle = circle(db, FakeFileSystem(), id)
        }
        val a = Device("device-a")
        val b = Device("device-b")
        val s = a.circle.explain(a.circle.start("akutagawa-kumo-no-ito")!!, 0, "A spider.")
        a.engine.sync(); b.engine.sync()
        val onB = assertNotNull(b.circle.session(s.id))
        assertEquals("A spider.", onB.entry(0).explain)
        clock.advance(5.seconds)
        b.circle.moveTo(onB, 2)
        b.engine.sync(); a.engine.sync()
        assertEquals(2, a.circle.session(s.id)?.position)
    }

    @Test
    fun theReviewSourceListsPoemsAndCircleSummaries() = runTest {
        val candidates = LinguistReviewSource(packDriver).candidates()
        val poem = candidates.single { it.kind == ReviewKind.POEM_ANNOTATION }
        assertEquals("nakahara-tsukiyo", poem.id)
        assertEquals(setOf("titleEn", "paraphrase", "gloss", "note"), poem.fields.keys)
        assertTrue("波打際【なみうちぎわ】" in poem.display)
        val circleText = candidates.single { it.kind == ReviewKind.CIRCLE_TEXT }
        assertEquals(setOf("titleEn", "summaryEn"), circleText.fields.keys)
        assertTrue(candidates.none { it.kind == ReviewKind.TRANSLATION_PASSAGE })
    }
}
