package app.tsumugi.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A time span in milliseconds, [startMs, endMs). */
data class Span(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/** Audio primitives for pronunciation analysis. Everything downstream works on 16 kHz mono floats in [-1, 1]. */
object Audio {
    const val SAMPLE_RATE = 16_000

    /** 10 ms analysis hop at 16 kHz. */
    const val HOP = 160

    /** 25 ms energy frame at 16 kHz. */
    const val FRAME = 400

    fun pcm16ToFloat(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32768f }

    /** Little-endian 16-bit PCM bytes (WAV data chunk, AudioRecord output) → floats. */
    fun pcm16LeToFloat(bytes: ByteArray): FloatArray = FloatArray(bytes.size / 2) { i ->
        val v = (bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)
        v.toShort() / 32768f
    }

    /**
     * Band-limited resampling with a Hann-windowed sinc (16 taps per side). When downsampling, the cutoff is
     * lowered to the new Nyquist frequency so nothing aliases.
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int = SAMPLE_RATE): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val ratio = toRate.toDouble() / fromRate
        val cutoff = min(1.0, ratio)
        val taps = 16
        val out = FloatArray(max(1, floor(input.size * ratio).toInt()))
        for (i in out.indices) {
            val t = i / ratio
            val center = floor(t).toInt()
            var acc = 0.0
            var norm = 0.0
            for (k in center - taps + 1..center + taps) {
                if (k < 0 || k >= input.size) continue
                val x = t - k
                val w = 0.5 + 0.5 * cos(PI * x / taps)
                val s = if (abs(x) < 1e-9) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
                val h = cutoff * s * w
                acc += input[k] * h
                norm += h
            }
            out[i] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }

    /** RMS per frame (frame [FRAME], hop [HOP]); frame i covers samples starting at i·hop. */
    fun rmsFrames(signal: FloatArray, frame: Int = FRAME, hop: Int = HOP): FloatArray {
        val n = frameCount(signal.size, hop)
        return FloatArray(n) { i ->
            val start = i * hop
            val end = min(signal.size, start + frame)
            var sum = 0.0
            for (j in start until end) sum += signal[j] * signal[j]
            if (end > start) sqrt(sum / (end - start)).toFloat() else 0f
        }
    }

    /** Zero-crossing rate per frame (crossings per sample). High for fricatives and noise. */
    fun zcrFrames(signal: FloatArray, frame: Int = FRAME, hop: Int = HOP): FloatArray {
        val n = frameCount(signal.size, hop)
        return FloatArray(n) { i ->
            val start = i * hop
            val end = min(signal.size, start + frame)
            var crossings = 0
            for (j in start + 1 until end) if ((signal[j] >= 0f) != (signal[j - 1] >= 0f)) crossings++
            if (end - start > 1) crossings.toFloat() / (end - start - 1) else 0f
        }
    }

    fun frameCount(samples: Int, hop: Int = HOP): Int = if (samples <= 0) 0 else (samples + hop - 1) / hop

    fun frameToMs(frame: Int, hop: Int = HOP, sampleRate: Int = SAMPLE_RATE): Long = frame.toLong() * hop * 1000 / sampleRate
}

/**
 * Energy + zero-crossing voice activity detection. The threshold adapts to the recording: a few times the noise
 * floor (10th-percentile frame energy), never below −54 dBFS. Short gaps are bridged by a hangover so a word
 * isn't split at every stop consonant.
 */
object VoiceActivity {
    private const val MIN_RMS = 0.002f // ≈ −54 dBFS
    private const val HANGOVER_FRAMES = 8 // 80 ms
    private const val MIN_SEGMENT_FRAMES = 6 // 60 ms

    fun speechFrames(signal: FloatArray): BooleanArray {
        val rms = Audio.rmsFrames(signal)
        if (rms.isEmpty()) return BooleanArray(0)
        val zcr = Audio.zcrFrames(signal)
        val floor = rms.sorted()[rms.size / 10]
        val peak = rms.max()
        val threshold = max(MIN_RMS, max(floor * 4f, peak * 0.03f))
        val raw = BooleanArray(rms.size) { i ->
            rms[i] > threshold || (rms[i] > threshold / 2 && zcr[i] > 0.25f && rms[i] > MIN_RMS)
        }
        // Hangover: keep "speech" for a few frames after it stops.
        val out = raw.copyOf()
        var since = Int.MAX_VALUE
        for (i in raw.indices) {
            if (raw[i]) since = 0 else if (since < HANGOVER_FRAMES) { out[i] = true; since++ } else since = Int.MAX_VALUE
        }
        return out
    }

    /** Speech segments in milliseconds. */
    fun segments(signal: FloatArray): List<Span> {
        val speech = speechFrames(signal)
        val out = ArrayList<Span>()
        var start = -1
        for (i in 0..speech.size) {
            val on = i < speech.size && speech[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                if (i - start >= MIN_SEGMENT_FRAMES) out += Span(Audio.frameToMs(start), Audio.frameToMs(i))
                start = -1
            }
        }
        return out
    }

    /** Gaps between speech segments of at least [minPauseMs] (hesitations; leading/trailing silence excluded). */
    fun pauses(segments: List<Span>, minPauseMs: Long = 250): List<Span> =
        segments.zipWithNext().map { (a, b) -> Span(a.endMs, b.startMs) }.filter { it.durationMs >= minPauseMs }

    fun voicedMs(segments: List<Span>): Long = segments.sumOf { it.durationMs }
}
