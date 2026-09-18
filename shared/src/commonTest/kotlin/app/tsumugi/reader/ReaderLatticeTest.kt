package app.tsumugi.reader

import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.Lemma
import app.tsumugi.dictionary.Token
import app.tsumugi.domain.Stage
import app.tsumugi.jp.FuriganaSegment
import app.tsumugi.jp.tokenizer.Morpheme
import app.tsumugi.jp.tokenizer.MorphologicalAnalyzer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-26 (reader uses the morphological analyzer) and F-39 (grammar hits respect word boundaries). */
class ReaderLatticeTest {
    private val dictionary = DictionaryRepository(DictionaryFixture.create())

    /** Returns IPADIC-shaped morphemes for the sentences these tests use (what LatticeTokenizer produces). */
    private class FakeAnalyzer : MorphologicalAnalyzer {
        val calls = mutableListOf<String>()
        override suspend fun analyze(text: String): List<Morpheme> {
            calls += text
            val parts: List<List<String>> = when (text) {
                // surface, pos, pos2, base, reading, conjugation type, conjugation form
                "私は学校に行きました。" -> listOf(
                    listOf("私", "名詞", "代名詞", "私", "ワタシ", "", ""),
                    listOf("は", "助詞", "係助詞", "は", "ハ", "", ""),
                    listOf("学校", "名詞", "一般", "学校", "ガッコウ", "", ""),
                    listOf("に", "助詞", "格助詞", "に", "ニ", "", ""),
                    listOf("行き", "動詞", "自立", "行く", "イキ", "五段・カ行促音便", "連用形"),
                    listOf("まし", "助動詞", "", "ます", "マシ", "特殊・マス", "連用形"),
                    listOf("た", "助動詞", "", "た", "タ", "特殊・タ", "基本形"),
                    listOf("。", "記号", "句点", "。", "。", "", ""),
                )
                else -> text.map { c -> listOf(c.toString(), "名詞", "一般", c.toString(), "", "", "") }
            }
            var at = 0
            return parts.map { p ->
                val s = p[0]
                Morpheme(
                    s, at, at + s.length, listOf(p[1], p[2]).filter { it.isNotEmpty() }, p[5].ifEmpty { null }, p[6].ifEmpty { null },
                    p[3], p[4].ifEmpty { null }, p[4].ifEmpty { null }, p[4].isEmpty(),
                ).also { at += s.length }
            }
        }
    }

    @Test
    fun readerTokensComeFromTheMorphologicalAnalyzer() = runTest {
        val morph = FakeAnalyzer()
        val stages = mapOf("jmdict:${DictionaryFixture.GAKKOU}" to Stage.GURU)
        val analyzer = ReaderAnalyzer(morph, dictionary, { stages }, { listOf("n5-masu" to Regex("ま(す|した|せん)")) })
        val body = "私は学校に行きました。"
        val sentence = analyzer.page(body, 0, 1).single().sentences.single()
        assertEquals(listOf(body), morph.calls)
        assertEquals(listOf("私", "は", "学校", "に", "行きました", "。"), sentence.tokens.map { it.surface })
        assertEquals(
            listOf(DictionaryFixture.WATASHI, DictionaryFixture.WA, DictionaryFixture.GAKKOU, DictionaryFixture.NI, DictionaryFixture.IKU, null),
            sentence.tokens.map { it.entryId },
        )
        val iki = sentence.tokens[4]
        assertEquals("行く", iki.dictionaryForm)
        assertEquals("いきました", iki.reading)
        assertEquals(listOf(FuriganaSegment("行", "い"), FuriganaSegment("きました")), iki.furigana)
        assertEquals(listOf("ます", "た"), iki.deinflection)
        assertTrue(sentence.tokens[2].known)
        assertEquals(listOf("n5-masu"), sentence.grammarPointIds)
    }

    @Test
    fun analysisReportsProgressPageByPage() = runTest {
        val analyzer = ReaderAnalyzer(FakeAnalyzer(), dictionary, { emptyMap() })
        val body = List(45) { "私は学校に行きました。" }.joinToString("\n")
        val progress = mutableListOf<Double>()
        val analysis = analyzer.analyzeWithProgress(body) { progress += it }
        assertEquals(45 * 5, analysis.wordCount)
        // 45 paragraphs = 3 pages of 20: start, one report per page, done.
        assertEquals(5, progress.size, "$progress")
        assertEquals(progress.sorted(), progress)
        assertEquals(0.0, progress.first())
        assertEquals(1.0, progress.last())
    }

    @Test
    fun lemmaLookupPrefersTheMatchingReading() = runTest {
        val ids = dictionary.entriesForLemmas(listOf(Lemma("行く", "いく"), Lemma("学校", null), Lemma("存在しない語", "そんざい")))
        assertEquals(DictionaryFixture.IKU, ids[Lemma("行く", "いく")])
        assertEquals(DictionaryFixture.GAKKOU, ids[Lemma("学校", null)])
        assertNull(ids[Lemma("存在しない語", "そんざい")])
    }

    // --- F-39 ---

    private fun tok(surface: String, start: Int, id: Long?, base: String? = surface, deinflection: List<String> = emptyList()) =
        Token(surface, start, start + surface.length, id, base, null, deinflection)

    private val patterns = listOf(
        "topic-wa" to Regex("は"),
        "masu" to Regex("ます"),
        "tai" to Regex("たい"),
    )

    private val analyzer = ReaderAnalyzer(dictionary, { emptyMap() })

    @Test
    fun particleInsideAWordIsNotAGrammarHit() {
        // おはよう is one word: its は is not the topic particle. ございます is ござる + ます: ます is an ending.
        val text = "おはようございます"
        val tokens = listOf(tok("おはよう", 0, 1), tok("ございます", 4, 2, base = "ござる", deinflection = listOf("ます")))
        assertEquals(listOf("masu"), analyzer.grammarHits(text, tokens, patterns))
    }

    @Test
    fun realParticleIsAHit() {
        val text = "私は学生です"
        val tokens = listOf(tok("私", 0, 1), tok("は", 1, 2), tok("学生", 2, 3), tok("です", 4, 4))
        assertEquals(listOf("topic-wa"), analyzer.grammarHits(text, tokens, patterns))
    }

    @Test
    fun endingInsideAnAdjectiveIsNotTai() {
        // 冷たい contains たい (the builder's own example); 食べたい really is ～たい.
        val cold = listOf(tok("冷たい", 0, 5))
        assertEquals(emptyList(), analyzer.grammarHits("冷たい", cold, patterns))
        val want = listOf(tok("食べたい", 0, 6, base = "食べる", deinflection = listOf("たい")))
        assertEquals(listOf("tai"), analyzer.grammarHits("食べたい", want, patterns))
    }

    @Test
    fun unknownTokensDoNotBlockMatches() {
        val tokens = listOf(tok("ほげは", 0, null))
        assertEquals(listOf("topic-wa"), analyzer.grammarHits("ほげは", tokens, patterns))
    }
}
