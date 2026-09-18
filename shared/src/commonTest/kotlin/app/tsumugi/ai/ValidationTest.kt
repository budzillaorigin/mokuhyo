package app.tsumugi.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ValidationTest {
    @Test
    fun scriptLeaks() {
        assertNull(Validation.scriptLeak("昨日、CDを買いました。"))
        assertNull(Validation.scriptLeak("ＮＨＫのニュースを見る。1990年"))
        assertNotNull(Validation.scriptLeak("私はgakkouに行きます"))
        assertNotNull(Validation.scriptLeak("これは the answer です"))
        assertNotNull(Validation.scriptLeak("안녕하세요"))
        assertNotNull(Validation.scriptLeak("привет"))
    }

    @Test
    fun requireJapaneseAndEnglish() {
        assertNull(Validation.requireJapanese("f", "猫が好きです"))
        assertEquals("f is not Japanese", Validation.requireJapanese("f", "I like cats"))
        assertEquals("f is empty", Validation.requireJapanese("f", " "))
        assertNull(Validation.requireEnglish("f", "The particle は marks the topic."))
        assertEquals("f is not English", Validation.requireEnglish("f", "これは説明です。"))
    }

    @Test
    fun levenshteinOverCodePoints() {
        assertEquals(0, Validation.levenshtein("猫", "猫"))
        assertEquals(1, Validation.levenshtein("学校に行く", "学校へ行く"))
        assertEquals(3, Validation.levenshtein("kitten", "sitting"))
        assertEquals(1, Validation.levenshtein("𠮷野家", "吉野家")) // surrogate pair counts as one
        assertEquals(3, Validation.levenshtein("", "abc"))
    }

    @Test
    fun minimalEditBound() {
        assertNull(Validation.minimalEdit("私は学校を行きます。", "私は学校に行きます。"))
        assertNotNull(Validation.minimalEdit("私は学校を行きます。", "昨日は友達と映画館で映画を見ました。"))
    }

    @Test
    fun lengthBounds() {
        assertNull(Validation.length("f", "あいう", max = 3))
        assertNotNull(Validation.length("f", "あいうえ", max = 3))
        assertNotNull(Validation.length("f", "", min = 1, max = 3))
    }
}
