package app.tsumugi.reader

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryEntry
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.domain.Stage
import app.tsumugi.jp.FuriganaSegment
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReaderAnalyzerTest {

    private val dictionary = DictionaryRepository(DictionaryFixture.create())
    private var stages = mapOf("jmdict:${DictionaryFixture.GAKKOU}" to Stage.GURU, "v:${DictionaryFixture.NEKO}" to Stage.APPRENTICE)
    private val analyzer = ReaderAnalyzer(dictionary, { stages }, { listOf("n5-masu" to Regex("ま(す|した|せん)")) })

    private val body = "私は学校に行きました。猫が食べた！\n寿司を食べる。"

    @Test
    fun paragraphsAndSentences() {
        val paragraphs = analyzer.paragraphs(body)
        assertEquals(2, paragraphs.size)
        val sentences = analyzer.sentences(body, paragraphs[0]).map { body.substring(it.first, it.last + 1) }
        assertEquals(listOf("私は学校に行きました。", "猫が食べた！"), sentences)
        val quoted = "彼は「行く。」と言った。"
        assertEquals(listOf("彼は「行く。」", "と言った。"), analyzer.sentences(quoted, quoted.indices).map { quoted.substring(it.first, it.last + 1) })
    }

    @Test
    fun tokensCarryFuriganaStageAndGrammar() = runTest {
        val page = analyzer.page(body, 0, 2)
        val first = page[0].sentences[0]
        assertEquals(listOf("私", "は", "学校", "に", "行きました", "。"), first.tokens.map { it.surface })
        assertEquals(listOf("n5-masu"), first.grammarPointIds)

        val gakkou = first.tokens[2]
        assertEquals(Stage.GURU, gakkou.stage)
        assertTrue(gakkou.known)
        assertEquals(listOf(FuriganaSegment("学校", "がっこう")), gakkou.furigana)
        assertEquals(2, gakkou.start)
        assertEquals("学校", body.substring(gakkou.start, gakkou.end))

        val iki = first.tokens[4]
        assertEquals("行く", iki.dictionaryForm)
        assertEquals("いきました", iki.reading)
        assertEquals(listOf(FuriganaSegment("行", "い"), FuriganaSegment("きました")), iki.furigana)

        val neko = page[0].sentences[1].tokens.first()
        assertEquals(Stage.APPRENTICE, neko.stage)
        assertTrue(neko.showFurigana(FuriganaMode.UNKNOWN_ONLY))
        assertTrue(!gakkou.showFurigana(FuriganaMode.UNKNOWN_ONLY), "known words hide furigana")
        assertTrue(!first.tokens[1].showFurigana(FuriganaMode.ALL), "kana needs no furigana")

        val sushi = page[1].sentences[0].tokens.first()
        assertEquals("寿司", sushi.surface)
        assertNull(sushi.jlpt)
        assertEquals(0, page[1].start - body.indexOf('\n') - 1)
    }

    @Test
    fun surfaceReadings() {
        assertEquals("たべた", ReaderAnalyzer.surfaceReading("食べた", "食べる", "たべる"))
        assertEquals("いって", ReaderAnalyzer.surfaceReading("行って", "行く", "いく"))
        assertEquals("きた", ReaderAnalyzer.surfaceReading("来た", "来る", "くる"))
        assertEquals("こない", ReaderAnalyzer.surfaceReading("来ない", "来る", "くる"))
        assertEquals("がっこう", ReaderAnalyzer.surfaceReading("学校", "学校", "がっこう"))
        assertNull(ReaderAnalyzer.surfaceReading("見た", "行く", "いく"), "unrelated shapes give no reading")
    }

    @Test
    fun rubyHintsWin() {
        val segs = ReaderAnalyzer.furigana("紳士が", 10, "しんしが", listOf(RubyHint(10, "紳士", "しんし")))
        assertEquals(listOf(FuriganaSegment("紳士", "しんし"), FuriganaSegment("が")), segs)
    }

    @Test
    fun difficulty() = runTest {
        val a = analyzer.analyze(body)
        assertEquals(9, a.wordCount, "私 は 学校 に 行きました 猫 食べた 寿司 食べる (が/を aren't in the fixture)")
        assertEquals(1.0 / 9, a.knownRatio)
        assertEquals(2.0 / 9, a.seenRatio)
        assertEquals(5, a.jlptEstimate, "everything but 寿司 is N5 in the fixture")
        assertNotNull(a.ilrEstimate)
        assertEquals("≈ N5 / ILR ${a.ilrEstimate}", a.levelLabel)

        val hard = ReaderAnalyzer(dictionary, { emptyMap() }).analyze("寿司寿司寿司。")
        assertEquals(0, hard.jlptEstimate, "no listed words → above N1")
    }

    @Test
    fun ilrScalesWithDifficulty() = runTest {
        val easy = analyzer.analyze("私はいきました。私はいきました。")
        val dense = analyzer.analyze("学校漢字学校漢字学校漢字学校漢字学校漢字学校漢字寿司寿司寿司寿司寿司寿司寿司。")
        assertEquals("0+", easy.ilrEstimate)
        assertTrue(dense.ilrEstimate > easy.ilrEstimate, "${dense.ilrEstimate} vs ${easy.ilrEstimate}")
    }

    @Test
    fun miningUsesSentenceAsContext() = runTest {
        val page = analyzer.page(body, 1, 1)
        val sentence = page.single().sentences.single()
        val token = sentence.tokens.first()
        var added: Pair<DictionaryEntry, String>? = null
        val id = analyzer.mine(token, sentence, { dictionary.entry(it)?.entry }, { e, ctx -> added = e to ctx; "jmdict:${e.id}" })
        assertEquals("jmdict:${DictionaryFixture.SUSHI}", id)
        assertEquals("寿司を食べる。", added!!.second)
        assertNull(analyzer.mine(sentence.tokens.first { it.surface == "。" }, sentence, { null }, { _, _ -> "x" }))
    }
}

class ReaderRepositoryTest {

    private val clock = TestClock()
    private val repo = ReaderRepository(TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema)), clock)

    @Test
    fun documentsAndFeeds() = runTest {
        val a = repo.save(ImportedText("一", "本文一", SourceKind.PASTE))
        clock.now = clock.now.plus(kotlin.time.Duration.parse("1m"))
        val b = repo.save(ImportedText("記事", "本文二", SourceKind.URL, "https://e.jp/1"))
        repo.setProgress(b, 2)
        val again = repo.save(ImportedText("記事（更新）", "本文三", SourceKind.URL, "https://e.jp/1"))
        assertEquals(b, again, "re-importing a URL replaces the document")
        assertEquals(listOf(b, a), repo.documents().map { it.id })
        val doc = assertNotNull(repo.document(b))
        assertEquals("本文三", doc.body)
        assertEquals(2, doc.progress, "reading position survives a refresh")

        repo.saveAnalysis(a, ReaderAnalysis(3, 0.5, 0.5, emptyMap(), 4, "1+", 12.0, 0.2, 0.0))
        assertEquals("≈ N4 / ILR 1+", repo.documents().first { it.id == a }.levelLabel)
        repo.delete(a)
        assertEquals(1, repo.documents().size)

        val f = repo.addFeed("https://e.jp/rss", "ニュース")
        repo.addFeed("https://e.jp/rss", "duplicate")
        assertEquals(listOf(f), repo.feeds())
        repo.markFetched(f.id)
        assertNotNull(repo.feeds().single().lastFetchedAt)
        repo.deleteFeed(f.id)
        assertTrue(repo.feeds().isEmpty())
    }
}
