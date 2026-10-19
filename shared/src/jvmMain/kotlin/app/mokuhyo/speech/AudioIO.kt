package app.mokuhyo.speech

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
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

    /** Inputs [record] can open: any of [CAPTURE_CANDIDATES], not only 16 kHz mono (QC 2026-10-19 #5). */
    fun inputDevices(): List<Device> = mixers { m -> CAPTURE_CANDIDATES.any { m.isLineSupported(DataLine.Info(TargetDataLine::class.java, it)) } }

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
                    val atLimit = synchronized(out) {
                        out.write(buf, 0, n)
                        out.size() >= format.sampleRate * format.frameSize * 120
                    }
                    if (atLimit) {
                        running.set(false)
                        onEvent(Vad.Event.AUTO_STOP)
                        break
                    }
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
            var line: TargetDataLine? = null
            try {
                line = (mixer?.getLine(info) ?: AudioSystem.getLine(info)) as TargetDataLine
                line.open(format)
                line.start()
                return Recording(line, format, onLevel, vad, onEvent)
            } catch (e: Exception) {
                runCatching { line?.close() }
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

    /** Mono PCM16 from [from] Hz to [to] Hz (same as [toMono16k]'s resampler; returns the input when the rates match). */
    fun resample(samples: ShortArray, from: Int, to: Int): ShortArray =
        if (from == to) samples else resampleTo(FloatArray(samples.size) { samples[it].toFloat() }, from.toFloat(), to.toFloat())

    private fun resample16k(mono: FloatArray, rate: Float): ShortArray = resampleTo(mono, rate, 16_000f)

    private fun resampleTo(mono: FloatArray, rate: Float, target: Float): ShortArray {
        if (mono.isEmpty()) return ShortArray(0)
        val ratio = rate / target
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

    /**
     * Audio ready to play: the output line is already open and started, so the device and format are known to work
     * before anything (a test's limited plays, say) is counted. [run] streams it; [close] releases it unplayed.
     */
    class Playback internal constructor(private val line: SourceDataLine, private val stream: AudioInputStream) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        /**
         * Streams to the end or until [stop] says so; returns true when it played to the end. Throws when the output
         * fails part-way (device unplugged, line error), which callers must tell apart from the learner's Stop.
         */
        fun run(stop: () -> Boolean = { false }): Boolean {
            try {
                val buf = ByteArray(4096)
                while (!stop()) {
                    val n = stream.read(buf)
                    if (n < 0) {
                        line.drain()
                        return true
                    }
                    var off = 0
                    while (off < n) {
                        // A running line blocks until it takes everything; writing nothing means the device went away.
                        val w = line.write(buf, off, n - off)
                        if (w <= 0) throw IllegalStateException("the audio output stopped during playback")
                        off += w
                        if (stop()) break
                    }
                }
                return false
            } finally {
                close()
            }
        }

        /** Releases the line; safe to call more than once (only the first call closes it). */
        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { line.stop() }
            runCatching { line.close() }
            runCatching { stream.close() }
        }
    }

    /**
     * Opens [audio] (WAV or AIFF) on [deviceName] (null = default) at [speed] (0.5–2.0×; below or above 1.0 the speech is
     * time-stretched so its pitch stays natural). Throws when the audio can't be decoded or the device can't be opened.
     */
    fun open(audio: ByteArray, deviceName: String? = null, speed: Double = 1.0): Playback {
        var stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(audio))
        if (speed != 1.0) {
            val rate = stream.format.sampleRate.toInt()
            val pcm = toMono16(stream)
            val stretched = TimeStretch.stretch(pcm, rate, speed.coerceIn(0.5, 2.0))
            stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(wav(stretched, rate)))
        }
        val format = stream.format
        val info = DataLine.Info(SourceDataLine::class.java, format)
        if (deviceName != null && mixerNamed(deviceName) == null) throw IllegalStateException("the output device “$deviceName” isn't connected")
        val line = mixerNamed(deviceName)?.let { AudioSystem.getMixer(it).getLine(info) as SourceDataLine } ?: AudioSystem.getLine(info) as SourceDataLine
        try {
            line.open(format)
            line.start()
        } catch (e: Exception) {
            runCatching { line.close() }
            throw e
        }
        return Playback(line, stream)
    }

    /**
     * Opens with [open] and plays to the end, owning the output line whatever is cancelled when (QC follow-up F3): it
     * is held from the moment it opens, so cancellation before, during or right after opening, or during playback,
     * still closes it exactly once. [started] runs on the caller's dispatcher once the line is open and the caller still
     * wants it (not [stop]ped, not cancelled), right before playback: that is where a test counts a play, so a play
     * is never counted for audio that didn't start. Returns true when it played to the end, false when stopped, null
     * when stopped before it started. Throws what [open] or playback throws; cancellation propagates.
     */
    suspend fun playOwned(open: () -> Playback, stop: () -> Boolean = { false }, started: () -> Unit = {}): Boolean? {
        val held = java.util.concurrent.atomic.AtomicReference<Playback?>()
        try {
            currentCoroutineContext().ensureActive()
            // Not cancellable: opening runs to the end and the line lands in [held] before control comes back here.
            withContext(Dispatchers.IO + NonCancellable) { held.set(open()) }
            currentCoroutineContext().ensureActive()
            if (stop()) return null
            started()
            val job = currentCoroutineContext().job
            return withContext(Dispatchers.IO) { held.get()!!.run { stop() || !job.isActive } }
        } finally {
            held.getAndSet(null)?.close()
        }
    }

    /** Plays WAV (or AIFF) bytes to [deviceName] (null = default) and returns when done or [stop] says so. */
    fun play(audio: ByteArray, deviceName: String? = null, speed: Double = 1.0, stop: () -> Boolean = { false }) {
        open(audio, deviceName, speed).run(stop)
    }

    /** A pack clip (Ogg Opus at its own rate, or WAV) as WAV bytes for the player. */
    fun clipWav(bytes: ByteArray): ByteArray =
        if (OggOpus.isOggOpus(bytes)) OggOpus.decodeWithRate(bytes).let { (pcm, rate) -> wav(pcm, rate) } else bytes

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
        val rate = src.format.sampleRate
        val mono = toMono16(src)
        return resample16k(FloatArray(mono.size) { mono[it].toFloat() }, rate)
    }

    /** Any WAV/AIFF → mono PCM16 at its own sample rate (no resampling: playback keeps the voice's full bandwidth). */
    fun toMonoPcm(audio: ByteArray): Pair<ShortArray, Int> {
        val src = AudioSystem.getAudioInputStream(ByteArrayInputStream(audio))
        return toMono16(src) to src.format.sampleRate.toInt()
    }

    private fun toMono16(src: AudioInputStream): ShortArray {
        val pcm = AudioSystem.getAudioInputStream(AudioFormat(src.format.sampleRate, 16, src.format.channels, true, false), src)
        val raw = pcm.readAllBytes()
        val channels = pcm.format.channels
        val frames = raw.size / (2 * channels)
        return ShortArray(frames) { f ->
            var sum = 0
            for (c in 0 until channels) {
                val i = (f * channels + c) * 2
                sum += ((raw[i + 1].toInt() shl 8) or (raw[i].toInt() and 0xFF)).toShort().toInt()
            }
            (sum / channels).toShort()
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
