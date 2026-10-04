package app.mokuhyo.ai

import app.mokuhyo.lang.ScriptCheck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ValidationTest {
    @Test
    fun scriptCheckPerLanguage() {
        assertNull(ScriptCheck.requireLanguage("f", "昨日、CDを買いました。", "ja"))
        assertNotNull(ScriptCheck.requireLanguage("f", "私はgakkouに行きます", "ja"))
        assertNotNull(ScriptCheck.requireLanguage("f", "انا أحب items الجميلة", "ar"))
        assertNull(ScriptCheck.requireLanguage("f", "مرحبا، كيف حالك؟ NATO", "ar"))
        assertNotNull(ScriptCheck.requireLanguage("f", "새로운 병원은 心血管 질환을 치료합니다", "ko"))
        assertNull(ScriptCheck.requireLanguage("f", "Pemerintah mengumumkan kebijakan baru.", "id"))
        // Spanish "a" is a preposition, not English (C-07 fix); code-switching into English is still caught.
        assertNull(ScriptCheck.requireLanguage("f", "¿Puedo ayudar a limpiar?", "es"))
        assertNotNull(ScriptCheck.requireLanguage("f", "Veo tu perspectiva, pero perhaps we should think about it differently.", "es"))
        assertNotNull(ScriptCheck.requireLanguage("f", "I like cats", "ru"))
        assertEquals("f is not in Spanish", ScriptCheck.requireLanguage("f", "Привет, как дела?", "es"))
        assertNull(ScriptCheck.requireEnglish("f", "Use the past tense here."))
        assertEquals("f is not English", ScriptCheck.requireEnglish("f", "過去形を使います"))
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
