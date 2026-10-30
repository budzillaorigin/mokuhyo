package app.mokuhyo.tts

import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.Delivery
import app.mokuhyo.speech.SpokenReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** An audio output line kept open for a whole reply (QC 2026-10-30 V4). */
interface PcmLine : AutoCloseable {
    val rate: Int

    /** Writes [samples] (16-bit mono at [rate]); blocks while the device buffer is full; throws when the device fails. */
    fun write(samples: ShortArray)

    /** Waits until everything written has played. */
    fun drain()
}

/** A [PcmLine] on the output device [deviceName] (null = default): 16-bit mono at [rate], opened and started. */
class DevicePcmLine private constructor(override val rate: Int, private val line: javax.sound.sampled.SourceDataLine) : PcmLine {
    override fun write(samples: ShortArray) {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v -> bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v.toInt() shr 8).toByte() }
        var off = 0
        while (off < bytes.size) {
            val w = line.write(bytes, off, bytes.size - off)
            if (w <= 0) throw IllegalStateException("the audio output stopped during playback")
            off += w
        }
    }

    override fun drain() = line.drain()

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.close() }
    }

    companion object {
        fun open(rate: Int, deviceName: String? = null): DevicePcmLine {
            val format = javax.sound.sampled.AudioFormat(rate.toFloat(), 16, 1, true, false)
            val info = javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format)
            val mixer = AudioIO.mixerNamed(deviceName)
            if (deviceName != null && mixer == null) throw IllegalStateException("the output device “$deviceName” isn't connected")
            val line = (mixer?.let { javax.sound.sampled.AudioSystem.getMixer(it).getLine(info) } ?: javax.sound.sampled.AudioSystem.getLine(info)) as javax.sound.sampled.SourceDataLine
            try {
                line.open(format)
                line.start()
            } catch (e: Exception) {
                runCatching { line.close() }
                throw e
            }
            return DevicePcmLine(rate, line)
        }
    }
}

/**
 * Plays a reply from a [EngineCapabilities.pcmStream] engine through one output line (QC 2026-10-30 V4): the line opens
 * at the first PCM and stays open across sentences, instead of one open-and-drain per sentence. PCM at another rate is
 * resampled to the line's (a format change never reopens the line); at most [buffer] chunks wait between the engine
 * and the device, so a fast engine can't fill memory. Cancelling the caller stops the engine and closes the line once.
 * Delivery per sentence comes back as [SpokenReply.Outcome], so the same repair policy applies (V10).
 */
class PcmReplyPlayer(private val open: (rate: Int) -> PcmLine, private val buffer: Int = 4) {
    private sealed interface Event {
        class Pcm(val chunk: PcmChunk) : Event
        class End(val index: Int, val text: String, val ok: Boolean) : Event
        class Skipped(val index: Int, val text: String) : Event
    }

    suspend fun speak(engine: SpeechEngine, requests: ReceiveChannel<SpeechRequest>): SpokenReply.Outcome = coroutineScope {
        val events = Channel<Event>(buffer)
        val deviceFailed = java.util.concurrent.atomic.AtomicBoolean(false)
        launch {
            try {
                var failed = false
                var index = 0
                for (r in requests) {
                    val i = index++
                    if (failed || deviceFailed.get()) { events.send(Event.Skipped(i, r.text)); continue }
                    val flow = engine.stream(r)
                    val ok = try {
                        flow?.collect { events.send(Event.Pcm(it)) }
                        flow != null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                    if (!ok) failed = true
                    events.send(Event.End(i, r.text, ok))
                }
            } finally {
                events.close()
            }
        }
        withContext(Dispatchers.IO) {
            var line: PcmLine? = null
            val utterances = ArrayList<SpokenReply.Utterance>()
            var played = 0
            var broken = false
            var heardAny = false
            var brokeInThisSentence = false
            try {
                for (e in events) when (e) {
                    is Event.Pcm -> if (!broken) {
                        try {
                            val l = line ?: open(e.chunk.rate).also { line = it }
                            l.write(if (e.chunk.rate == l.rate) e.chunk.samples else AudioIO.resample(e.chunk.samples, e.chunk.rate, l.rate))
                            heardAny = true
                        } catch (x: CancellationException) {
                            throw x
                        } catch (x: Exception) {
                            broken = true
                            brokeInThisSentence = true
                            deviceFailed.set(true)
                        }
                    }
                    is Event.End -> utterances += SpokenReply.Utterance(e.index, e.text, when {
                        brokeInThisSentence -> if (heardAny) Delivery.INTERRUPTED else Delivery.FAILED
                        broken -> Delivery.GENERATED
                        !e.ok -> Delivery.FAILED.also { broken = true }
                        else -> Delivery.COMPLETED.also { played++ }
                    }).also { heardAny = false; brokeInThisSentence = false }
                    is Event.Skipped -> utterances += SpokenReply.Utterance(e.index, e.text, Delivery.GENERATED)
                }
                if (!broken) line?.drain()
            } finally {
                line?.close()
            }
            SpokenReply.Outcome(played, broken, utterances)
        }
    }
}
