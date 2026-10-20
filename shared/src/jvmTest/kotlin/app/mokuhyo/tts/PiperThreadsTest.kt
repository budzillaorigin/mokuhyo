package app.mokuhyo.tts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PiperThreadsTest {
    private val catalog = VoiceCatalog(File("missing-piper"), emptyList(), "test")

    @Test
    fun theVoiceThreadCapReachesThePiperProcess() {
        val env = PiperEngine(catalog, threads = { 2 }).processBuilder(listOf("piper")).environment()
        assertEquals("2", env["MOKUHYO_TTS_THREADS"])
        assertFalse("MOKUHYO_TTS_THREADS" in PiperEngine(catalog).processBuilder(listOf("piper")).environment(), "no cap = ONNX Runtime's default")
    }
}
