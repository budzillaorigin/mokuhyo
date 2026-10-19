package app.mokuhyo.speech

import app.mokuhyo.lang.Languages
import app.mokuhyo.lang.VoiceSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadinessTest {
    private val piper = setOf("es", "fr", "de", "pt-BR", "ru", "fa")
    private val os = setOf("ja", "ko")

    private fun voices(lang: String) = when (lang) {
        in piper -> listOf(VoiceSpec("$lang-piper", lang, "female", "piper", "CC0"))
        in os -> listOf(VoiceSpec("$lang-os", lang, "male", "os", "system"))
        else -> emptyList()
    }

    @Test
    fun thePanelCoversAllElevenLanguages() {
        val rows = LanguageReadiness.all(::voices, "Whisper small", "Phi-4-mini", mapOf("es" to MeasuredLatency(2_400, 3_900, 30, 0)))
        assertEquals(11, rows.size)
        assertEquals(Languages.all.map { it.code }, rows.map { it.language })
        rows.forEach { assertEquals(4, it.lines().size, it.language) }
        val es = rows.single { it.language == "es" }
        assertEquals(LanguageReadiness.VoiceSource.PIPER, es.voiceSource)
        assertTrue(es.lines()[3].contains("p50 2.4 s"), es.lines()[3])
        assertEquals(LanguageReadiness.VoiceSource.OS, rows.single { it.language == "ja" }.voiceSource)
    }

    @Test
    fun aLanguageWithoutAVoiceSaysSoAndOffersTextPractice() {
        val ar = LanguageReadiness.all(::voices, null, null, emptyMap()).single { it.language == "ar" }
        assertFalse(ar.spoken)
        assertEquals(LanguageReadiness.VoiceSource.NONE, ar.voiceSource)
        assertTrue(ar.lines()[0].contains("guided text practice"))
        assertTrue(ar.lines()[1].contains("typed"))
        assertTrue(ar.lines()[3].contains("not measured"))
    }
}
