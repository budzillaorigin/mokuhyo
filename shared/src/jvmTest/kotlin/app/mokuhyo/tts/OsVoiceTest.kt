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

    /** BRIEF_PHASE8 N-00(a): scripts go to PowerShell as -EncodedCommand, so quotes, spaces and Japanese survive. */
    @Test
    fun powershellScriptsAreEncodedNotQuoted() {
        val script = OsVoice.sapiSpeakScript("Microsoft Haruka Desktop", 1.0, "C:\\Users\\Taro Yamada\\in put.txt", "C:\\Temp\\O'Brien\\音声.wav")
        val cmd = OsVoice.powershell(script)
        assertEquals("-EncodedCommand", cmd[cmd.size - 2])
        assertTrue(cmd.none { '"' in it || ' ' in it }, "no argument carries quotes or spaces for the launcher to mangle: $cmd")
        val decoded = String(java.util.Base64.getDecoder().decode(cmd.last()), Charsets.UTF_16LE)
        assertEquals(script, decoded)
        assertTrue("'C:\\Temp\\O''Brien\\音声.wav'" in decoded, "single quotes doubled, Japanese intact")
        assertTrue("SelectVoice('Microsoft Haruka Desktop')" in decoded)
        val winrt = OsVoice.winrtSpeakScript("HKEY_LOCAL_MACHINE\\SOFTWARE\\Microsoft\\Speech_OneCore\\Voices\\Tokens\\MSTTS_V110_jaJP_HarukaM", 1.25, "in.txt", "out.wav")
        assertTrue("SpeakingRate = 1.25" in winrt && "MSTTS_V110_jaJP_HarukaM'" in winrt)
        assertEquals(winrt, String(java.util.Base64.getDecoder().decode(OsVoice.encode(winrt)), Charsets.UTF_16LE))
    }

    /** SAPI and WinRT listings merge: OneCore-only voices appear, duplicates keep SAPI, online voices are dropped. */
    @Test
    fun windowsListingMergesSapiAndWinRt() {
        val out = """
            sapi|Microsoft Zira Desktop|en-US|Female
            sapi|Microsoft Haruka Desktop|ja-JP|Female
            winrt|Microsoft Haruka|ja-JP|Female|TOKEN_HARUKA
            winrt|Microsoft Ayumi|ja-JP|Female|TOKEN_AYUMI
            winrt|Microsoft Ichiro|ja-JP|Male|TOKEN_ICHIRO
            winrt|Microsoft Nanami Online (Natural) - Japanese (Japan)|ja-JP|Female|TOKEN_ONLINE
            Microsoft David Desktop|en-US|Male
        """.trimIndent()
        val vs = OsVoice.parseWindowsVoices(out)
        assertEquals(listOf("Microsoft Zira Desktop", "Microsoft Haruka Desktop", "Microsoft David Desktop", "Microsoft Ayumi", "Microsoft Ichiro"), vs.map { it.name })
        assertEquals("sapi", vs.first { it.name.startsWith("Microsoft Haruka") }.api)
        assertEquals("TOKEN_AYUMI", vs.first { it.name == "Microsoft Ayumi" }.id)
        assertEquals(listOf("Microsoft Ayumi", "Microsoft Haruka Desktop", "Microsoft Ichiro"), OsVoice.voicesFor("ja", vs).map { it.name })
    }

    /** macOS: a compact-only language gets the System Settings hint; an Enhanced voice silences it. */
    @Test
    fun compactOnlyHintOnMac() {
        if (Os.current != Os.MACOS) return
        val compact = listOf(OsVoice.Info("Kyoko", "ja_JP", "female"))
        assertTrue(OsVoice.compactOnlyHint("ja", compact)!!.contains("Spoken Content"))
        assertNull(OsVoice.compactOnlyHint("ja", compact + OsVoice.Info("Kyoko (Enhanced)", "ja_JP", "female")))
        assertNull(OsVoice.compactOnlyHint("ko", compact))
    }
}
