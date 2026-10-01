package app.mokuhyo.tts

import app.mokuhyo.platform.Os
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsVoiceTest {
    private val sayOutput = """
        Anna                de_DE    # Hallo! Ich heiße Anna.
        Eddy (German (Germany)) de_DE    # Hallo! Ich heiße Eddy.
        Flo (Spanish (Mexico)) es_MX    # ¡Hola! Me llamo Flo.
        Kyoko               ja_JP    # こんにちは! 私の名前はKyokoです。
        Kyoko (Enhanced)    ja_JP    # こんにちは! 私の名前はKyokoです。
        Majed               ar_001   # مرحبًا! اسمي ماجد.
        Mónica              es_ES    # ¡Hola! Me llamo Mónica.
        Paulina             es_MX    # ¡Hola! Me llamo Paulina.
        Luciana             pt_BR    # Olá, meu nome é Luciana.
        Joana               pt_PT    # Olá, chamo-me Joana.
        Tingting            zh_CN    # 你好！我叫婷婷。
        Meijia              zh_TW    # 你好，我叫美佳。
        Samantha            en_US    # Hello! My name is Samantha.
    """.trimIndent()

    @Test
    fun parsesSayListingAndPicksByLocale() {
        val all = OsVoice.parseSayVoices(sayOutput)
        assertEquals(13, all.size)
        assertEquals(OsVoice.Info("Eddy (German (Germany))", "de_DE", "male"), all[1])
        assertEquals(listOf("Anna", "Eddy (German (Germany))"), OsVoice.voicesFor("de", all).map { it.name }, "Eloquence voices last")
        assertEquals(listOf("Mónica", "Paulina", "Flo (Spanish (Mexico))"), OsVoice.voicesFor("es", all).map { it.name }, "es_ES first")
        assertEquals("Kyoko (Enhanced)", OsVoice.voicesFor("ja", all).first().name, "enhanced voice preferred")
        assertEquals(listOf("Majed"), OsVoice.voicesFor("ar", all).map { it.name })
        assertEquals(listOf("Luciana"), OsVoice.voicesFor("pt-BR", all).map { it.name }, "pt_PT is not pt-BR")
        assertEquals(listOf("Tingting"), OsVoice.voicesFor("zh-Hans", all).map { it.name }, "zh_TW is not zh-Hans")
        assertTrue(OsVoice.voicesFor("fa", all).isEmpty(), "no Persian voice: nothing, not the English default")
        assertTrue(OsVoice.voicesFor("ko", all).isEmpty())
    }

    @Test
    fun parsesSapiListing() {
        val all = OsVoice.parseSapiVoices("Microsoft Haruka Desktop|ja-JP|Female\nMicrosoft David Desktop|en-US|Male\n\nbroken line\n")
        assertEquals(2, all.size)
        assertEquals(listOf("Microsoft Haruka Desktop"), OsVoice.voicesFor("ja", all).map { it.name })
        assertEquals("female", all[0].gender)
        assertTrue(OsVoice.voicesFor("fa", all).isEmpty())
    }

    @Test
    fun realOsVoices() {
        if (!OsVoice.available) {
            println("SKIP realOsVoices: no OS voice on ${Os.current}")
            assertTrue(OsVoice.voices().isEmpty())
            assertNull(OsVoice.synthesize("Hola", "es"))
            return
        }
        val voices = OsVoice.voices()
        println("os voices: ${voices.size}; ja=${OsVoice.voicesFor("ja").map { it.name }} fa=${OsVoice.voicesFor("fa").map { it.name }}")
        if (OsVoice.voicesFor("fa").isEmpty()) {
            assertNull(OsVoice.synthesize("سلام", "fa"), "no Persian voice -> null, not English reading Persian")
            assertTrue(OsSpeechEngine().voicesFor("fa").isEmpty())
        }
        val ja = OsVoice.voicesFor("ja").firstOrNull()
        if (ja == null) {
            println("SKIP realOsVoices synthesis: no Japanese OS voice installed")
            return
        }
        val wav = assertNotNull(OsVoice.synthesize("こんにちは。今日はいい天気ですね。", "ja"))
        val info = assertNotNull(Wav.info(wav))
        println("os ${ja.name}: ${"%.2f".format(info.durationSeconds)} s")
        assertTrue(info.durationSeconds in 0.8..10.0)
    }
}
