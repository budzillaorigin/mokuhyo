package app.mokuhyo.speech

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.sqrt

/**
 * Microphone capture and playback through javax.sound.sampled (BRIEF §3.3). Capture is 16 kHz mono signed 16-bit,
 * the format Whisper wants. Blocking calls; run them on Dispatchers.IO.
 */
object AudioIO {
    val CAPTURE_FORMAT = AudioFormat(16_000f, 16, 1, true, false)

    data class Device(val name: String, val description: String)

    fun inputDevices(): List<Device> = mixers { it.isLineSupported(DataLine.Info(TargetDataLine::class.java, CAPTURE_FORMAT)) }

    fun outputDevices(): List<Device> =
        mixers { it.isLineSupported(DataLine.Info(SourceDataLine::class.java, AudioFormat(22_050f, 16, 1, true, false))) }

    private fun mixers(supports: (Mixer) -> Boolean): List<Device> = AudioSystem.getMixerInfo().mapNotNull { info ->
        runCatching { AudioSystem.getMixer(info) }.getOrNull()?.takeIf { runCatching { supports(it) }.getOrDefault(false) }
            ?.let { Device(info.name, info.description) }
    }

    private fun mixerNamed(name: String?): Mixer.Info? = name?.let { n -> AudioSystem.getMixerInfo().firstOrNull { it.name == n } }

    /**
     * An open recording at the device's own [format] (BRIEF_PHASE8 N-00b: many inputs only run at 44.1/48 kHz);
     * [stop] ends it and returns 16 kHz mono PCM, gain-normalized. With a [vad], [onEvent] reports speech start,
     * auto-stop after the silence that ends a turn, and "no audio" when the line stays at the noise floor.
     */
    class Recording internal constructor(
        private val line: TargetDataLine,
        val format: AudioFormat,
        private val onLevel: (Double) -> Unit,
        private val vad: Vad?,
        private val onEvent: (Vad.Event) -> Unit,
    ) {
        private val running = AtomicBoolean(true)
        private val out = ByteArrayOutputStream()
        private val thread = Thread({
            val frameBytes = (format.sampleRate / 10).toInt() * format.frameSize // 100 ms
            val buf = ByteArray(frameBytes)
            while (running.get()) {
                val n = line.read(buf, 0, buf.size)
                if (n > 0) {
                    synchronized(out) { out.write(buf, 0, n) }
                    val level = rms(buf, n)
                    onLevel(level)
                    vad?.feed(level)?.let(onEvent)
                }
            }
        }, "mokuhyo-capture").apply { isDaemon = true; start() }

        fun stop(): ShortArray {
            running.set(false)
            line.stop()
            line.close()
            thread.join(1_000)
            val bytes = synchronized(out) { out.toByteArray() }
            return Gain.normalize(toMono16k(bytes, format.sampleRate.toInt(), format.channels))
        }
    }

    /** Capture formats tried in order: the rates real devices run at, then the 16 kHz Whisper rate; mono, then stereo. */
    internal val CAPTURE_CANDIDATES: List<AudioFormat> = listOf(48_000f, 44_100f, 16_000f).flatMap { rate ->
        listOf(AudioFormat(rate, 16, 1, true, false), AudioFormat(rate, 16, 2, true, false))
    }

    /** Starts capturing from [deviceName] (null = system default). [onLevel] gets 0..1 RMS every 100 ms. */
    fun record(deviceName: String? = null, onLevel: (Double) -> Unit = {}, vad: Vad? = null, onEvent: (Vad.Event) -> Unit = {}): Recording {
        val mixer = mixerNamed(deviceName)?.let { AudioSystem.getMixer(it) }
        var last: Exception? = null
        for (format in CAPTURE_CANDIDATES) {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val supported = mixer?.isLineSupported(info) ?: AudioSystem.isLineSupported(info)
            if (!supported) continue
            try {
                val line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
                line.open(format)
                line.start()
                return Recording(line, format, onLevel, vad, onEvent)
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException("no usable microphone format" + (last?.message?.let { ": $it" } ?: ""), last)
    }

    /** Little-endian PCM16 at [rate] with [channels] → 16 kHz mono (averaged channels, linear interpolation). */
    fun toMono16k(bytes: ByteArray, rate: Int, channels: Int): ShortArray {
        val frames = bytes.size / (2 * channels)
        val mono = FloatArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                sum += (bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)
            }
            sum.toFloat() / channels
        }
        return resample16k(mono, rate.toFloat())
    }

    private fun resample16k(mono: FloatArray, rate: Float): ShortArray {
        if (mono.isEmpty()) return ShortArray(0)
        val ratio = rate / 16_000f
        // Average over each output step before interpolating: a cheap low-pass against aliasing from 44.1/48 kHz.
        val smoothed = if (ratio > 1.5f) {
            val w = ratio.toInt()
            FloatArray(mono.size) { i -> var s = 0f; var n = 0; for (k in i until minOf(i + w, mono.size)) { s += mono[k]; n++ }; s / n }
        } else mono
        val outLen = (mono.size / ratio).toInt()
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val a = pos.toInt().coerceAtMost(mono.size - 1)
            val b = (a + 1).coerceAtMost(mono.size - 1)
            val t = pos - a
            (smoothed[a] * (1 - t) + smoothed[b] * t).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Plays WAV (or AIFF) bytes to [deviceName] (null = default) and returns when done or [stop] says so. */
    fun play(audio: ByteArray, deviceName: String? = null, speed: Double = 1.0, stop: () -> Boolean = { false }) {
        val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(audio))
        val base = stream.format
        // Speed 0.8–1.0×: resample by playing at a lower sample rate (pitch drops slightly; acceptable for practice).
        val format = AudioFormat(base.encoding, (base.sampleRate * speed).toFloat(), base.sampleSizeInBits, base.channels, base.frameSize,
            (base.frameRate * speed).toFloat(), base.isBigEndian)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = mixerNamed(deviceName)?.let { AudioSystem.getMixer(it).getLine(info) as SourceDataLine } ?: AudioSystem.getLine(info) as SourceDataLine
        line.open(format)
        line.start()
        val buf = ByteArray(4096)
        AudioInputStream(stream, base, stream.frameLength).use { s ->
            while (!stop()) {
                val n = s.read(buf)
                if (n < 0) break
                line.write(buf, 0, n)
            }
        }
        if (!stop()) line.drain()
        line.stop()
        line.close()
    }

    /** 16-bit PCM → WAV bytes. */
    fun wav(samples: ShortArray, sampleRate: Int = 16_000): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s ->
            bytes[2 * i] = s.toByte()
            bytes[2 * i + 1] = (s.toInt() shr 8).toByte()
        }
        val out = ByteArrayOutputStream()
        AudioSystem.write(
            AudioInputStream(ByteArrayInputStream(bytes), AudioFormat(sampleRate.toFloat(), 16, 1, true, false), samples.size.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE, out,
        )
        return out.toByteArray()
    }

    /** Any WAV/AIFF → 16 kHz mono PCM16 (linear interpolation resampling), for Whisper. */
    fun toPcm16kMono(audio: ByteArray): ShortArray {
        val src = AudioSystem.getAudioInputStream(ByteArrayInputStream(audio))
        val pcm = AudioSystem.getAudioInputStream(
            AudioFormat(src.format.sampleRate, 16, src.format.channels, true, false), src,
        )
        val raw = pcm.readAllBytes()
        val channels = pcm.format.channels
        val frames = raw.size / (2 * channels)
        val mono = FloatArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                sum += (raw[i + 1].toInt() shl 8) or (raw[i].toInt() and 0xFF)
            }
            sum.toFloat() / channels
        }
        return resample16k(mono, pcm.format.sampleRate)
    }

    fun rms(buf: ByteArray, n: Int): Double {
        var sum = 0.0
        val count = n / 2
        for (i in 0 until count) {
            val s = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort() / 32768.0
            sum += s * s
        }
        return if (count == 0) 0.0 else sqrt(sum / count).coerceIn(0.0, 1.0)
    }
}
