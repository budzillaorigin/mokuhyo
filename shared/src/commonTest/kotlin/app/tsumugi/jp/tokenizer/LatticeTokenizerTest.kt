package app.tsumugi.jp.tokenizer

import app.tsumugi.testing.inMemoryDriver
import app.tsumugi.tokenizer.db.Char_category
import app.tsumugi.tokenizer.db.Char_range
import app.tsumugi.tokenizer.db.Connection
import app.tsumugi.tokenizer.db.Pos
import app.tsumugi.tokenizer.db.TokenizerDatabase
import app.tsumugi.tokenizer.db.Unknown
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LatticeTokenizerTest {

    /** A tiny pack shaped like build_tokenizer.py output: all connection costs 0, so word costs decide. */
    private val tokenizer = LatticeTokenizer(TokenizerDatabase(inMemoryDriver(TokenizerDatabase.Schema)).also { db ->
        val q = db.tokenizerQueries
        listOf(
            "名詞,一般,*,*,*,*", "助詞,係助詞,*,*,*,*", "動詞,自立,*,*,五段・カ行促音便,連用形", "助動詞,*,*,*,特殊・マス,基本形",
            "名詞,固有名詞,一般,*,*,*", "記号,一般,*,*,*,*", "名詞,数,*,*,*,*", "助詞,格助詞,一般,*,*,*",
        ).forEachIndexed { i, f -> q.insertPos(Pos(i.toLong(), f)) }
        fun word(surface: String, seq: Long, pos: Long, cost: Long, reading: String, base: String = "") =
            q.insertMorpheme(app.tsumugi.tokenizer.db.Morpheme(surface, seq, 1, 1, cost, pos, base, reading, ""))
        word("私", 0, 0, 100, "ワタシ")
        word("は", 0, 1, 100, "ハ")
        word("学校", 0, 0, 100, "ガッコウ")
        word("学", 0, 0, 300, "ガク")
        word("校", 0, 0, 300, "コウ")
        word("に", 0, 7, 100, "ニ")
        word("行き", 0, 2, 100, "イキ", base = "行く")
        word("ます", 0, 3, 100, "マス")
        q.insertConnection(Connection(0, 4, 4, ByteArray(32)))
        listOf(
            Char_category("DEFAULT", 0, 1, 0), Char_category("SPACE", 0, 1, 0), Char_category("KANJI", 0, 0, 2),
            Char_category("HIRAGANA", 0, 1, 2), Char_category("KATAKANA", 1, 1, 2), Char_category("NUMERIC", 1, 1, 0),
            Char_category("SYMBOL", 1, 1, 0),
        ).forEach(q::insertCategory)
        listOf(
            0x20 to 0x20 to "SPACE", 0x30 to 0x39 to "NUMERIC", 0x3000 to 0x3000 to "SPACE", 0x3001 to 0x3002 to "SYMBOL",
            0x3041 to 0x309F to "HIRAGANA", 0x30A1 to 0x30FF to "KATAKANA", 0x4E00 to 0x9FFF to "KANJI",
        ).forEachIndexed { i, (range, cats) -> q.insertRange(Char_range(i.toLong(), range.first.toLong(), range.second.toLong(), cats)) }
        listOf("KATAKANA" to 4L, "NUMERIC" to 6L, "SYMBOL" to 5L, "KANJI" to 0L, "HIRAGANA" to 0L, "DEFAULT" to 5L).forEach { (cat, pos) ->
            q.insertUnknown(Unknown(cat, 0, 1, 1, if (cat == "KANJI" || cat == "HIRAGANA") 2000 else 500, pos))
        }
    })

    private suspend fun surfaces(text: String) = tokenizer.analyze(text).map { it.surface }

    @Test
    fun segmentsWithDictionaryWords() = runTest {
        val m = tokenizer.analyze("私は学校に行きます")
        assertEquals(listOf("私", "は", "学校", "に", "行き", "ます"), m.map { it.surface })
        assertEquals(listOf("名詞", "助詞", "名詞", "助詞", "動詞", "助動詞"), m.map { it.pos.first() })
        assertEquals(listOf("名詞", "一般"), m[0].pos)
        val iki = m[4]
        assertEquals("行く", iki.baseForm)
        assertEquals("五段・カ行促音便", iki.conjugationType)
        assertEquals("連用形", iki.conjugationForm)
        assertEquals("イキ", iki.reading)
        assertEquals("イキ", iki.pronunciation, "pronunciation defaults to the reading")
        assertEquals("学校", m[2].baseForm, "base form defaults to the surface")
    }

    @Test
    fun cheapestPathWins() = runTest {
        assertEquals(listOf("学校"), surfaces("学校"), "one 100-cost word beats two 300-cost words")
    }

    @Test
    fun offsetsAndWhitespace() = runTest {
        val m = tokenizer.analyze("私 は　学校")
        assertEquals(listOf("私", "は", "学校"), m.map { it.surface })
        assertEquals(listOf(0 to 1, 2 to 3, 4 to 6), m.map { it.start to it.end })
    }

    @Test
    fun unknownWordsGroupByCategory() = runTest {
        val m = tokenizer.analyze("テスト123。")
        assertEquals(listOf("テスト", "123", "。"), m.map { it.surface })
        assertEquals(listOf(true, true, true), m.map { it.isUnknown })
        assertEquals(listOf("名詞", "名詞", "記号"), m.map { it.pos.first() })
        assertEquals("数", m[1].pos[1])
        assertNull(m[0].reading)
    }

    @Test
    fun unknownKanjiUpToCategoryLength() = runTest {
        // KANJI: no grouping, candidates of length 1..2 → the two-character unknown is cheaper than two singles.
        assertEquals(listOf("猫犬", "は"), surfaces("猫犬は"))
    }

    @Test
    fun sentencesSplitAndKeepOffsets() = runTest {
        val m = tokenizer.analyze("私は。学校に。")
        assertEquals(listOf("私", "は", "。", "学校", "に", "。"), m.map { it.surface })
        assertEquals(3, m[3].start)
        assertTrue(m.zipWithNext().all { (a, b) -> a.end <= b.start })
    }

    @Test
    fun emptyInput() = runTest {
        assertEquals(emptyList(), tokenizer.analyze(""))
        assertEquals(emptyList(), tokenizer.analyze("   "))
    }
}
