package app.tsumugi.writing

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.FakeModel
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.Token
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.reader.GradedPassage
import app.tsumugi.reader.GradedPassageSummary
import app.tsumugi.reader.GradedStory
import app.tsumugi.reader.ReaderAnalyzer
import app.tsumugi.reader.ReaderTask
import app.tsumugi.reader.ReaderTaskKind
import app.tsumugi.reader.ReaderTaskSet
import app.tsumugi.srs.SrsRepository
import app.tsumugi.sync.FakeSyncServer
import app.tsumugi.sync.SyncEngine
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import app.tsumugi.thesaurus.ClusterKind
import app.tsumugi.thesaurus.CollocationPattern
import app.tsumugi.thesaurus.ThesaurusRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** BRIEF_V2 §6.13: the register rules, drafts and their sync, thesaurus flags, corrections, collocations (D-272…D-275). */
class WritingStudioTest {
    private val clock = TestClock()

    @Test
    fun registerOfASentenceComesFromItsEndingAndKeigo() {
        fun r(s: String) = RegisterChecker.classify(s).register
        assertEquals(SpeechRegister.POLITE, r("明日は雨が降るそうです。"))
        assertEquals(SpeechRegister.POLITE, r("もう食べましたか？"))
        assertEquals(SpeechRegister.POLITE, r("ここに名前を書いてくださいね。"))
        assertEquals(SpeechRegister.FORMAL, r("社長は会議室にいらっしゃいます。"))
        assertEquals(SpeechRegister.FORMAL, r("後ほどお電話いたします。"))
        assertEquals(SpeechRegister.FORMAL, r("これは重要な問題である。"))
        assertEquals(SpeechRegister.CASUAL, r("明日は雨だよね。"))
        assertEquals(SpeechRegister.CASUAL, r("もう食べた？"))
        assertEquals(SpeechRegister.CASUAL, r("それ、すごいじゃん！"))
        assertEquals(SpeechRegister.CASUAL, r("駅まで歩く。"))
        assertNull(r("第一章"), "a heading has no predicate")
        assertTrue(RegisterChecker.classify("それ、すごいじゃん！").markers.any { it.startsWith("casual") })
    }

    @Test
    fun theReportFindsTheSentencesOutOfLine() {
        val text = "昨日、友達と映画を見ました。とても面白かった。また行きたいです。"
        val report = RegisterChecker.check(text)
        assertEquals(SpeechRegister.POLITE, report.dominant)
        assertTrue(report.mixed)
        assertEquals(listOf("とても面白かった。"), report.outliers.map { it.text })
        val o = report.outliers.single()
        assertEquals("とても面白かった。", text.substring(o.start, o.end))
        val asCasual = RegisterChecker.check(text, SpeechRegister.CASUAL)
        assertEquals(2, asCasual.outliers.size, "against a target, the other register is what's flagged")
        assertTrue(!RegisterChecker.check("今日は晴れです。明日も晴れでしょう。").mixed)
    }

    // A thesaurus in an in-memory dictionary pack: one cluster whose plain word is 怒る.
    private fun thesaurus(): ThesaurusRepository {
        val driver = inMemoryDriver(DictionaryDatabase.Schema)
        driver.execute(null, "INSERT INTO sentence VALUES (7, '彼は腹を立てた。', 'He got angry.', 4)", 0)
        driver.execute(null, "INSERT INTO expression_cluster VALUES ('anger', 0, 'emotion', '怒り', 'いかり', 'Anger', 'From irritation to fury.', 'llm', 0)", 0)
        driver.execute(null, "INSERT INTO expression VALUES ('anger', 0, '腹を立てる', 'はらをたてる', 1234, 'to get angry', 'Everyday.', 'neutral', 2, '彼は腹を立てた。', 'He got angry.')", 0)
        driver.execute(null, "INSERT INTO expression VALUES ('anger', 1, 'むっと', 'むっと', NULL, '', 'A flash.', 'neutral', 1, 'むっとした。', 'Offended.')", 0)
        driver.execute(null, "INSERT INTO expression_example VALUES ('anger', 0, 7)", 0)
        driver.execute(null, "INSERT INTO expression_plain VALUES ('怒る', 'anger')", 0)
        driver.execute(null, "INSERT INTO collocation VALUES (10, 20, 'NV', 'が', '雨', '降る', 482, 6.4, 7)", 0)
        driver.execute(null, "INSERT INTO collocation VALUES (30, 10, 'AN', '', '強い', '雨', 4, 3.4, NULL)", 0)
        return ThesaurusRepository(DictionaryDatabase(driver))
    }

    // Reader tokens for the one test sentence (the lattice tokenizer's lemmas).
    private val analyzer = ReaderAnalyzer(
        tokenize = { s ->
            if (s.startsWith("彼はすごく怒った")) {
                listOf(Token("彼", 0, 1, 1L, "彼", "かれ"), Token("は", 1, 2, null, "は", "は"), Token("すごく", 2, 5, 2L, "すごい", "すごい"), Token("怒った", 5, 8, 3L, "怒る", "おこる"), Token("。", 8, 9, null, null, null))
            } else emptyList()
        },
        summaries = { emptyList() },
        stages = { emptyMap() },
    )

    private fun studio(db: TsumugiDatabase, model: FakeModel? = null) = WritingStudio(
        db, { thesaurus() }, { analyzer }, { AiGateway({ model }) }, { null },
        gradeReaderSummary = { id, text -> if (id == "gr-n3-001" && text.isNotBlank()) null else null }, clock = clock,
    )

    @Test
    fun plainWordsAreFlaggedWithTheirClusters() = runTest {
        val flags = studio(TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))).suggestions("彼はすごく怒った。")
        val f = flags.single()
        assertEquals("怒った" to "怒る", f.surface to f.lemma)
        assertEquals(5 to 8, f.start to f.end)
        assertEquals(listOf("anger"), f.clusters.map { it.id })
        assertTrue(f.clusters.single().isAiGenerated)
    }

    @Test
    fun theThesaurusListsClustersExpressionsExamplesAndCollocations() = runTest {
        val t = thesaurus()
        assertTrue(t.available())
        assertEquals(listOf("anger"), t.clusters(ClusterKind.EMOTION).map { it.id })
        assertTrue(t.clusters(ClusterKind.SCENE).isEmpty())
        val detail = assertNotNull(t.cluster("anger"))
        assertEquals(listOf("腹を立てる", "むっと"), detail.expressions.map { it.text })
        assertEquals(listOf(7L), detail.expressions[0].tatoeba.map { it.sentenceId })
        assertNull(detail.expressions[1].entryId)
        assertEquals(listOf("anger"), t.search("腹").map { it.id })
        assertEquals(listOf("anger"), t.clustersForEntry(1234))
        val c = t.collocations(10)
        assertEquals(listOf("雨が降る", "強い雨"), c.map { it.phrase })
        assertEquals(CollocationPattern.NV, c[0].pattern)
        assertEquals("彼は腹を立てた。", c[0].example?.ja)
        assertTrue(ThesaurusRepository(DictionaryDatabase(inMemoryDriver(DictionaryDatabase.Schema))).clusters().isEmpty())
    }

    @Test
    fun draftsSaveAndSyncLastWriterWins() = runTest {
        val server = FakeSyncServer()
        class Device(id: String) {
            val driver = inMemoryDriver(TsumugiDatabase.Schema)
            val db = TsumugiDatabase(driver)
            val engine = SyncEngine(driver, db, SrsRepository(db, id, clock), id, server, null, clock)
            val studio = studio(db)
        }
        val a = Device("device-a")
        val b = Device("device-b")
        val d = a.studio.createDraft("日記", "今日は雨だった。", SpeechRegister.CASUAL)
        a.engine.sync(); b.engine.sync()
        assertEquals("今日は雨だった。", b.studio.draft(d.id)?.body)
        assertEquals(SpeechRegister.CASUAL, b.studio.draft(d.id)?.targetRegister)
        clock.advance(5.seconds)
        b.studio.update(d.id, "日記", "今日は雨だった。傘を忘れた。", SpeechRegister.CASUAL)
        b.engine.sync(); a.engine.sync()
        assertEquals("今日は雨だった。傘を忘れた。", a.studio.draft(d.id)?.body)
        clock.advance(5.seconds)
        a.studio.delete(d.id)
        a.engine.sync(); b.engine.sync()
        assertTrue(b.studio.drafts().isEmpty())
    }

    @Test
    fun aReaderOutputTaskOpensOneDraftCarryingTheTask() = runTest {
        val studio = studio(TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema)))
        val output = ReaderTask(ReaderTaskKind.OUTPUT, 4, "記事を日本語で要約しましょう。", "記事を日本語で要約しましょう。", "Summarize the article in Japanese.")
        val story = GradedStory(
            GradedPassage(GradedPassageSummary("gr-n3-001", "graded", "町の図書館", 3, "1+", 400, "llm", "N3", "news"), "本文。", emptyList(), null),
            "N3", "news", "", "The town library", 20, "N3", 1.0, emptyList(), emptyList(), emptyList(),
            ReaderTaskSet("news", null, null, emptyList(), output), emptyList(),
        )
        val d = studio.draftForReaderTask(story)
        assertEquals("reader:gr-n3-001", d.taskRef)
        assertEquals("gr-n3-001", d.readerStoryId)
        assertEquals("記事を日本語で要約しましょう。", d.taskPrompt)
        assertEquals(d.id, studio.draftForReaderTask(story).id, "the same task reopens the same draft")
        assertNull(studio.gradeReaderTask(studio.createDraft("free")), "a free draft has no task to grade")
    }

    @Test
    fun correctionsGoSentenceBySentenceAndNothingIsFakedWithoutAModel() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val good = """{"is_correct":false,"corrected":"昨日、図書館で本を読みました。","confidence":0.9,"edits":[{"original":"読みます","replacement":"読みました","reason":"昨日 needs the past tense."}],"explanation":"Past tense."}"""
        val ok = """{"is_correct":true,"corrected":"楽しかったです。","confidence":0.9,"edits":[],"explanation":"Fine."}"""
        val list = studio(db, FakeModel(good, ok)).corrections("昨日、図書館で本を読みます。楽しかったです。")
        assertEquals(2, list.size)
        assertEquals("昨日、図書館で本を読みました。", list[0].result?.corrected)
        assertEquals("llm", list[0].source)
        assertEquals(true, list[1].result?.isCorrect)
        val offline = studio(db).corrections("昨日、図書館で本を読みます。")
        assertTrue(offline.single().result == null && offline.single().unavailable != null)
        assertNull(studio(db).readability(""), "no score for an empty draft")
    }
}
