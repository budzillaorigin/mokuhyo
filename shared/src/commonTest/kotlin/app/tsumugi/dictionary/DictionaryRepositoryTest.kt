package app.tsumugi.dictionary

import app.tsumugi.dictionary.DictionaryFixture.GAKKOU
import app.tsumugi.dictionary.DictionaryFixture.IKU
import app.tsumugi.dictionary.DictionaryFixture.KAKU
import app.tsumugi.dictionary.DictionaryFixture.KANJI
import app.tsumugi.dictionary.DictionaryFixture.KANJI_FEELING
import app.tsumugi.dictionary.DictionaryFixture.NEKO
import app.tsumugi.dictionary.DictionaryFixture.NI
import app.tsumugi.dictionary.DictionaryFixture.SUSHI
import app.tsumugi.dictionary.DictionaryFixture.TABERU
import app.tsumugi.dictionary.DictionaryFixture.WA
import app.tsumugi.dictionary.DictionaryFixture.WATASHI
import app.tsumugi.jp.Conjugation
import app.tsumugi.jp.FuriganaSegment
import app.tsumugi.jp.PitchPattern
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DictionaryRepositoryTest {

    private val repo = DictionaryRepository(DictionaryFixture.create())

    private suspend fun ids(query: String) = repo.search(query).hits.map { it.entry.id }

    @Test
    fun exactKanjiAndKanaMatches() = runTest {
        assertEquals(TABERU, ids("食べる").first())
        assertEquals(TABERU, ids("たべる").first())
        assertEquals(NEKO, ids("ネコ").first(), "katakana folds to hiragana for search")
    }

    @Test
    fun deinflectedMatchesCarryReasons() = runTest {
        val hit = repo.search("食べました").hits.first()
        assertEquals(TABERU, hit.entry.id)
        assertEquals(MatchKind.DEINFLECTED, hit.match)
        assertTrue(hit.deinflection.isNotEmpty())
        assertEquals(KAKU, ids("書いた").first())
        assertEquals(IKU, ids("行って").first())
    }

    @Test
    fun deinflectionRespectsPartOfSpeech() = runTest {
        // 猫 is a noun, so "猫った" must not match it as a verb.
        assertTrue(NEKO !in ids("猫った"))
    }

    @Test
    fun englishSearch() = runTest {
        assertEquals(NEKO, ids("cat").first())
        assertEquals(TABERU, ids("to eat").first())
        assertEquals(SearchMode.ENGLISH, repo.search("cat").mode)
        assertTrue(ids("character").contains(KANJI))
    }

    @Test
    fun romajiSearchFindsKanaWords() = runTest {
        val results = ids("kanji")
        assertTrue(KANJI in results && KANJI_FEELING in results)
        assertEquals(SUSHI, ids("sushi").first())
    }

    @Test
    fun prefixSearch() = runTest {
        val results = repo.search("かん").hits
        assertTrue(results.all { it.match == MatchKind.PREFIX })
        assertEquals(setOf(KANJI, KANJI_FEELING), results.map { it.entry.id }.toSet())
    }

    @Test
    fun sentenceModeTokenizesAndDeinflects() = runTest {
        val results = repo.search("私は学校に行きます")
        assertEquals(SearchMode.SENTENCE, results.mode)
        assertEquals(listOf("私", "は", "学校", "に", "行きます"), results.tokens.map { it.surface })
        assertEquals(listOf(WATASHI, WA, GAKKOU, NI, IKU), results.tokens.map { it.entryId })
        assertEquals("行く", results.tokens.last().dictionaryForm)
    }

    @Test
    fun tokenizerPassesThroughUnknownAndLatin() = runTest {
        val tokens = repo.tokenize("猫、OK")
        assertEquals(listOf("猫", "、", "OK"), tokens.map { it.surface })
        assertEquals(listOf(NEKO, null, null), tokens.map { it.entryId })
    }

    @Test
    fun entryDetail() = runTest {
        val detail = assertNotNull(repo.entry(TABERU))
        assertEquals("食べる", detail.entry.headword)
        assertEquals(listOf(FuriganaSegment("食", "た"), FuriganaSegment("べる")), detail.furigana)
        assertEquals(PitchPattern.NAKADAKA, detail.pitch.single().pattern)
        assertEquals(listOf("食"), detail.kanji.map { it.literal })
        assertEquals("寿司を食べる。", detail.sentences.single().japanese)
        assertTrue(detail.conjugations.any { it.conjugation == Conjugation.POLITE && it.text == "食べます" })
    }

    @Test
    fun furiganaFallsBackToAligner() = runTest {
        val detail = assertNotNull(repo.entry(KAKU))
        assertEquals(listOf(FuriganaSegment("書", "か"), FuriganaSegment("く")), detail.furigana)
    }

    @Test
    fun kanjiDetail() = runTest {
        val detail = assertNotNull(repo.kanji("猫"))
        assertEquals("cat", detail.info.keyword)
        assertEquals(setOf("犭", "艹", "田"), detail.components.toSet())
        assertEquals(listOf(NEKO), detail.words.map { it.id })
        assertEquals(2, repo.kanji("田")!!.strokes.size)
    }

    @Test
    fun radicalSearchNarrows() = runTest {
        val field = repo.kanjiByRadicals(setOf("田"))
        assertEquals(listOf("田", "畑", "猫"), field.kanji.map { it.literal }, "sorted by stroke count")
        assertTrue("火" in field.compatibleRadicals && "良" !in field.compatibleRadicals)
        assertEquals(listOf("畑"), repo.kanjiByRadicals(setOf("田", "火")).kanji.map { it.literal })
    }
}
