package app.tsumugi.speech

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** F0 track: one value per 10 ms frame, Hz or NaN when unvoiced, with a voicing probability (1 − aperiodicity). */
class F0Track(val f0: FloatArray, val voicing: FloatArray) {
    val size: Int get() = f0.size
    val voicedCount: Int get() = f0.count { !it.isNaN() }

    /** Median F0 of voiced frames (the speaker's reference), or NaN. */
    val medianHz: Float
        get() = f0.filter { !it.isNaN() }.sorted().let { if (it.isEmpty()) Float.NaN else it[it.size / 2] }

    /** Semitones relative to [referenceHz] (default: the speaker's median), NaN where unvoiced. */
    fun semitones(referenceHz: Float = medianHz): FloatArray =
        FloatArray(size) { i -> if (f0[i].isNaN() || referenceHz.isNaN()) Float.NaN else (12.0 * ln(f0[i] / referenceHz.toDouble()) / ln(2.0)).toFloat() }
}

/**
 * YIN fundamental-frequency estimation (de Cheveigné & Kawahara 2002): difference function, cumulative mean
 * normalized difference, absolute threshold, parabolic interpolation. 16 kHz input, 10 ms hop, 40 ms window,
 * 70–500 Hz. Frames too quiet to be speech are skipped (NaN) to keep it fast.
 */
object Yin {
    private const val WINDOW = 640 // 40 ms
    private const val MIN_HZ = 70
    private const val MAX_HZ = 500
    private const val THRESHOLD = 0.15f
    private const val UNVOICED_ABOVE = 0.35f

    fun track(signal: FloatArray, sampleRate: Int = Audio.SAMPLE_RATE): F0Track {
        val hop = sampleRate / 100
        val tauMin = sampleRate / MAX_HZ
        val tauMax = sampleRate / MIN_HZ
        val window = WINDOW * sampleRate / Audio.SAMPLE_RATE
        val integration = window - tauMax
        val frames = Audio.frameCount(signal.size, hop)
        val f0 = FloatArray(frames) { Float.NaN }
        val voicing = FloatArray(frames)
        val rms = Audio.rmsFrames(signal, window, hop)
        val gate = max(0.002f, (rms.maxOrNull() ?: 0f) * 0.02f)
        val d = FloatArray(tauMax + 2)
        val cmnd = FloatArray(tauMax + 2)
        for (frame in 0 until frames) {
            val start = frame * hop
            if (start + window + 1 > signal.size || rms[frame] < gate) continue
            // Difference function.
            for (tau in 1..tauMax) {
                var sum = 0f
                for (j in 0 until integration) {
                    val diff = signal[start + j] - signal[start + j + tau]
                    sum += diff * diff
                }
                d[tau] = sum
            }
            // Cumulative mean normalized difference.
            cmnd[0] = 1f
            var running = 0f
            for (tau in 1..tauMax) {
                running += d[tau]
                cmnd[tau] = if (running > 0f) d[tau] * tau / running else 1f
            }
            // First dip below the threshold, then follow it down to its local minimum.
            var best = -1
            var tau = tauMin
            while (tau <= tauMax) {
                if (cmnd[tau] < THRESHOLD) {
                    while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                    best = tau
                    break
                }
                tau++
            }
            if (best < 0) {
                best = (tauMin..tauMax).minBy { cmnd[it] }
                if (cmnd[best] > UNVOICED_ABOVE) {
                    voicing[frame] = 1f - min(1f, cmnd[best])
                    continue
                }
            }
            voicing[frame] = 1f - min(1f, cmnd[best])
            f0[frame] = (sampleRate / parabolic(cmnd, best, tauMin, tauMax)).toFloat()
        }
        return F0Track(medianSmooth(f0), voicing)
    }

    private fun parabolic(y: FloatArray, x: Int, lo: Int, hi: Int): Double {
        if (x <= lo || x >= hi) return x.toDouble()
        val a = y[x - 1]
        val b = y[x]
        val c = y[x + 1]
        val denom = a - 2 * b + c
        return if (denom == 0f) x.toDouble() else x + 0.5 * (a - c) / denom
    }

    /** 5-frame median over voiced neighbours; removes octave jumps and single-frame glitches. NaN stays NaN. */
    internal fun medianSmooth(f0: FloatArray, radius: Int = 2): FloatArray = FloatArray(f0.size) { i ->
        if (f0[i].isNaN()) return@FloatArray Float.NaN
        val window = (max(0, i - radius)..min(f0.lastIndex, i + radius)).map { f0[it] }.filter { !it.isNaN() }.sorted()
        window[window.size / 2]
    }
}
