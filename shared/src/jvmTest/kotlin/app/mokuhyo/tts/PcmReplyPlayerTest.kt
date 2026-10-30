package app.mokuhyo.tts

import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.Delivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** QC 2026-10-30 V4: a PCM-streaming engine plays a whole reply through one output line. A fake engine only. */
class PcmReplyPlayerTest {
    private val voice = VoiceSpec("fake", "es", "female", "fake-stream", "test")

    private class FakeLine(override val rate: Int, val written: MutableList<Int>, private val slowMs: Long = 0, private val failAfter: Int = Int.MAX_VALUE) : PcmLine {
        val closed = AtomicInteger()
        override fun write(samples: ShortArray) {
            if (written.size >= failAfter) throw IllegalStateException("device gone")
            if (slowMs > 0) Thread.sleep(slowMs)
            written += samples.size
        }
        override fun drain() {}
        override fun close() { closed.incrementAndGet() }
    }

    /** Three chunks of 100 samples per sentence; [rates] per sentence index; produced counts every chunk handed out. */
    private class FakeEngine(val rates: List<Int> = listOf(24_000), val produced: AtomicInteger = AtomicInteger(), val chunks: Int = 3) : SpeechEngine {
        override val engine = "fake-stream"
        override val capabilities = EngineCapabilities(wavOnly = false, pcmStream = true)
        var index = 0
        override fun voicesFor(language: String) = emptyList<VoiceSpec>()
        override suspend fun synthesize(text: String, voice: VoiceSpec, speed: Double): ByteArray? = null
        override fun stream(request: SpeechRequest): Flow<PcmChunk> {
            val rate = rates[(index++).coerceAtMost(rates.lastIndex)]
            return flow { repeat(chunks) { produced.incrementAndGet(); emit(PcmChunk(ShortArray(100) { 1_000 }, rate)) } }
        }
    }

    private fun requests(vararg texts: String) = Channel<SpeechRequest>(Channel.UNLIMITED).apply {
        texts.forEach { trySend(SpeechRequest(voice, "es", it, intent = SpeechRequest.intentOf(it))) }
        close()
    }

    @Test
    fun aThreeSentenceReplyOpensTheLineOnce() = runBlocking {
        val opens = AtomicInteger()
        val written = ArrayList<Int>()
        val lines = ArrayList<FakeLine>()
        val outcome = PcmReplyPlayer({ r -> opens.incrementAndGet(); FakeLine(r, written).also { lines += it } })
            .speak(FakeEngine(), requests("Muy bien.", "Gracias.", "¿Dónde trabaja?"))
        assertEquals(1, opens.get(), "one output line for the whole reply")
        assertEquals(9, written.size)
        assertEquals(1, lines.single().closed.get())
        assertEquals(listOf(Delivery.COMPLETED, Delivery.COMPLETED, Delivery.COMPLETED), outcome.utterances.map { it.delivery })
    }

    @Test
    fun aFormatChangeIsResampledIntoTheSameLine() = runBlocking {
        val opens = AtomicInteger()
        val written = ArrayList<Int>()
        PcmReplyPlayer({ r -> opens.incrementAndGet(); FakeLine(r, written) }).speak(FakeEngine(listOf(24_000, 16_000)), requests("Uno.", "Dos."))
        assertEquals(1, opens.get())
        assertEquals(listOf(100, 100, 100), written.take(3))
        assertTrue(written.drop(3).all { it == 150 }, "16 kHz chunks resampled to the 24 kHz line: $written")
    }

    @Test
    fun theBufferIsBoundedAndCancellationClosesTheLineOnce() = runBlocking {
        val produced = AtomicInteger()
        val written = ArrayList<Int>()
        val line = CompletableDeferred<FakeLine>()
        val maxAhead = AtomicInteger()
        val engine = FakeEngine(produced = produced, chunks = 200)
        val job = async {
            PcmReplyPlayer({ r -> FakeLine(r, written, slowMs = 5).also { line.complete(it) } }, buffer = 4).speak(engine, requests("Una frase larga."))
        }
        val l = withTimeout(5_000) { line.await() }
        repeat(20) { Thread.sleep(5); maxAhead.set(maxOf(maxAhead.get(), produced.get() - written.size)) }
        job.cancel()
        runCatching { job.await() }
        assertTrue(maxAhead.get() <= 4 + 2, "at most the buffer waits ahead of the device: ${maxAhead.get()}")
        assertTrue(produced.get() < 200, "cancellation stopped the engine")
        assertEquals(1, l.closed.get(), "closed exactly once")
    }

    @Test
    fun aDeviceFailureInterruptsThatSentenceAndNothingAfterPlays() = runBlocking {
        val written = ArrayList<Int>()
        val outcome = PcmReplyPlayer({ r -> FakeLine(r, written, failAfter = 4) }).speak(FakeEngine(), requests("Uno.", "Dos.", "Tres."))
        assertEquals(listOf(Delivery.COMPLETED, Delivery.INTERRUPTED, Delivery.GENERATED), outcome.utterances.map { it.delivery })
        assertEquals(listOf("Uno."), outcome.heard)
    }

    @Test
    fun shippedEnginesAreWavOnly() {
        val catalog = VoiceCatalog.discover()
        assertTrue(PiperEngine(catalog).capabilities.let { it.wavOnly && !it.pcmStream })
        assertTrue(OsSpeechEngine().capabilities.let { it.wavOnly && !it.pcmStream })
        assertEquals(null, VoiceService(listOf(PiperEngine(catalog), OsSpeechEngine())).pcmStreamFor("es", null))
    }
}
