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

    /** An open recording; [stop] ends it and returns the samples. */
    class Recording internal constructor(private val line: TargetDataLine, private val onLevel: (Double) -> Unit) {
        private val running = AtomicBoolean(true)
        private val out = ByteArrayOutputStream()
        private val thread = Thread({
            val buf = ByteArray(3200) // 100 ms
            while (running.get()) {
                val n = line.read(buf, 0, buf.size)
                if (n > 0) {
                    out.write(buf, 0, n)
                    onLevel(rms(buf, n))
                }
            }
        }, "mokuhyo-capture").apply { isDaemon = true; start() }

        fun stop(): ShortArray {
            running.set(false)
            line.stop()
            line.close()
            thread.join(1_000)
            val bytes = out.toByteArray()
            return ShortArray(bytes.size / 2) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xFF)).toShort() }
        }
    }

    /** Starts capturing from [deviceName] (null = system default). [onLevel] gets 0..1 RMS every 100 ms. */
    fun record(deviceName: String? = null, onLevel: (Double) -> Unit = {}): Recording {
        val info = DataLine.Info(TargetDataLine::class.java, CAPTURE_FORMAT)
        val line = mixerNamed(deviceName)?.let { AudioSystem.getMixer(it).getLine(info) as TargetDataLine }
            ?: AudioSystem.getLine(info) as TargetDataLine
        line.open(CAPTURE_FORMAT)
        line.start()
        return Recording(line, onLevel)
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
        val ratio = pcm.format.sampleRate / 16_000f
        val outLen = (frames / ratio).toInt()
        return ShortArray(outLen) { i ->
            val pos = i * ratio
            val a = pos.toInt().coerceAtMost(frames - 1)
            val b = (a + 1).coerceAtMost(frames - 1)
            val t = pos - a
            (mono[a] * (1 - t) + mono[b] * t).toInt().coerceIn(-32768, 32767).toShort()
        }
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
