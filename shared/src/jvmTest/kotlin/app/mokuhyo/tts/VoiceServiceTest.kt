package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fallback order and voice choice with fake engines; always runs (no Piper or OS voice needed). */
class VoiceServiceTest {
    private class FakeEngine(
        override val engine: String,
        private val voices: List<VoiceSpec>,
        private val behavior: suspend (VoiceSpec) -> ByteArray? = { wav(1.0) },
    ) : SpeechEngine {
        val calls = mutableListOf<String>()
        override fun voicesFor(language: String) = voices.filter { it.language == language }
        override suspend fun synthesize(text: String, voice: VoiceSpec, speed: Double): ByteArray? {
            calls += voice.id
            return behavior(voice)
        }
    }

    private fun piper(id: String, lang: String, gender: String) = VoiceSpec(id, lang, gender, "piper", "CC0-1.0")
    private fun os(id: String, lang: String, gender: String) = VoiceSpec(id, lang, gender, "os", "os")

    @Test
    fun piperFirstThenOsThenNull(): Unit = runBlocking {
        val p = FakeEngine("piper", listOf(piper("es-m", "es", "male")))
        val o = FakeEngine("os", listOf(os("os:Monica", "es", "female"), os("os:Kyoko", "ja", "female")))
        val service = VoiceService(listOf(p, o))

        assertEquals("es-m", service.synthesize("Hola", "es")?.voice?.id, "bundled voice wins")
        assertEquals("os:Kyoko", service.synthesize("こんにちは", "ja")?.voice?.id, "no bundled ja voice -> OS voice")
        assertNull(service.synthesize("سلام", "fa"), "no voice at all -> null, never a wrong-language voice")
        assertEquals(listOf("es-m", "os:Monica"), service.voicesFor("es").map { it.id })
        assertTrue(service.voicesFor("fa").isEmpty())
    }

    @Test
    fun failingBundledVoiceFallsThroughToOsVoice(): Unit = runBlocking {
        val p = FakeEngine("piper", listOf(piper("es-f", "es", "female"), piper("es-m", "es", "male"))) { v ->
            if (v.id == "es-f") throw IllegalStateException("process died") else null
        }
        val o = FakeEngine("os", listOf(os("os:Monica", "es", "female")))
        val got = VoiceService(listOf(p, o)).synthesize("Hola", "es")
        assertEquals("os:Monica", got?.voice?.id)
        assertEquals(listOf("es-f", "es-m"), p.calls, "every bundled voice was tried first")
    }

    @Test
    fun invalidAudioCountsAsFailure(): Unit = runBlocking {
        val p = FakeEngine("piper", listOf(piper("es-m", "es", "male"))) { ByteArray(10) }
        val o = FakeEngine("os", listOf(os("os:Monica", "es", "female")))
        assertEquals("os:Monica", VoiceService(listOf(p, o)).synthesize("Hola", "es")?.voice?.id)
    }

    @Test
    fun requestedGenderFirstWithinEachEngine(): Unit = runBlocking {
        val p = FakeEngine("piper", listOf(piper("de-m", "de", "male"), piper("de-f", "de", "female")))
        val o = FakeEngine("os", listOf(os("os:Markus", "de", "male"), os("os:Anna", "de", "female")))
        val service = VoiceService(listOf(p, o))
        val female = VoiceSpec("any", "de", "female", "piper", "")
        assertEquals(listOf("de-f", "de-m", "os:Anna", "os:Markus"), service.candidates("de", female).map { it.id })
        assertEquals("de-f", service.synthesize("Hallo", "de", female)?.voice?.id)
        // No bundled voice of the requested gender: the bundled voice still beats the OS voice.
        val pt = FakeEngine("piper", listOf(piper("pt-m", "pt-BR", "male")))
        val ptOs = FakeEngine("os", listOf(os("os:Luciana", "pt-BR", "female")))
        assertEquals("pt-m", VoiceService(listOf(pt, ptOs)).synthesize("Olá", "pt-BR", VoiceSpec("x", "pt-BR", "female", "piper", ""))?.voice?.id)
    }

    @Test
    fun explicitVoiceIsTriedFirst(): Unit = runBlocking {
        val p = FakeEngine("piper", listOf(piper("de-m", "de", "male"), piper("de-f", "de", "female")))
        val o = FakeEngine("os", listOf(os("os:Anna", "de", "female")))
        val got = VoiceService(listOf(p, o)).synthesize("Hallo", "de", os("os:Anna", "de", "female"))
        assertEquals("os:Anna", got?.voice?.id)
    }

    @Test
    fun blankTextAndSpeedClamp(): Unit = runBlocking {
        var seen = 0.0
        val engine = object : SpeechEngine {
            override val engine = "piper"
            override fun voicesFor(language: String) = listOf(piper("es-m", "es", "male"))
            override suspend fun synthesize(text: String, voice: VoiceSpec, speed: Double): ByteArray? {
                seen = speed
                return wav(1.0)
            }
        }
        val service = VoiceService(listOf(engine))
        assertNull(service.synthesize("   ", "es"))
        service.synthesize("Hola", "es", speed = 0.1)
        assertEquals(VoiceService.MIN_SPEED, seen)
    }

    @Test
    fun cancellationIsNotSwallowedAsAFallback(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val p = FakeEngine("piper", listOf(piper("es-m", "es", "male"))) {
            started.complete(Unit)
            awaitCancellation()
        }
        val o = FakeEngine("os", listOf(os("os:Monica", "es", "female")))
        val job = async { VoiceService(listOf(p, o)).synthesize("Hola", "es") }
        started.await()
        yield()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(o.calls.isEmpty(), "a cancelled request does not go on to the OS voice")
    }

    @Test
    fun wavInfoMeasuresDuration() {
        val info = assertNotNull(Wav.info(wav(1.5)))
        assertEquals(22050, info.sampleRate)
        assertEquals(1.5, info.durationSeconds, 0.001)
        assertNull(Wav.info(ByteArray(100)))
    }

    @Test
    fun requestJsonIsAsciiAndEscaped() {
        val json = PiperEngine.requestJson("Él dijo \"hola\"\nسلام", File("/tmp/a b.wav"), 1, 1.25)
        assertTrue(json.all { it.code in 0x20..0x7e }, json)
        assertTrue(json.contains("\\u00c9l dijo \\\"hola\\\"\\n\\u0633"), json)
        assertTrue(json.contains("\"length_scale\":1.2500"), json)
        assertTrue(json.contains("\"speaker_id\":1"), json)
        assertTrue(!PiperEngine.requestJson("x", File("/tmp/x.wav"), null, 1.0).contains("speaker_id"))
    }

    companion object {
        /** A silent 16-bit mono 22.05 kHz WAV of [seconds]. */
        fun wav(seconds: Double): ByteArray {
            val samples = (22050 * seconds).toInt()
            val data = samples * 2
            return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(22050); putInt(44100)
                putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data)
            }.array()
        }
    }
}
