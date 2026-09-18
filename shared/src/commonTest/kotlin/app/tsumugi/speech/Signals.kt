package app.tsumugi.speech

import kotlin.math.PI
import kotlin.math.sin

/** Deterministic synthetic test signals at 16 kHz. */
internal object Signals {
    const val SR = 16000

    fun silence(ms: Int): FloatArray = FloatArray(SR * ms / 1000)

    /** Low-level white noise (LCG, deterministic) — a realistic "silent room" floor. */
    fun noise(ms: Int, amplitude: Float = 0.0005f, seed: Long = 1): FloatArray {
        var state = seed
        return FloatArray(SR * ms / 1000) {
            state = (state * 6364136223846793005L + 1442695040888963407L)
            ((state ushr 33).toDouble() / (1L shl 30) - 1.0).toFloat() * amplitude
        }
    }

    /** Harmonic tone whose frequency follows [hzAt] (t in seconds); [harmonics] partials with 1/k amplitude. */
    fun tone(ms: Int, harmonics: Int = 1, amplitude: Float = 0.3f, hzAt: (Double) -> Double): FloatArray {
        val n = SR * ms / 1000
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            phase += 2 * PI * hzAt(i.toDouble() / SR) / SR
            var v = 0.0
            for (k in 1..harmonics) v += sin(k * phase) / k
            // 5 ms fade in/out to avoid clicks.
            val fade = minOf(1.0, i / 80.0, (n - 1 - i) / 80.0)
            out[i] = (v * amplitude * fade).toFloat()
        }
        return out
    }

    fun steady(ms: Int, hz: Double, harmonics: Int = 1) = tone(ms, harmonics) { hz }

    /** Consecutive pitch steps with continuous phase (one voiced stretch), e.g. a mora-by-mora accent pattern. */
    fun steps(msEach: Int, vararg hz: Double, harmonics: Int = 4): FloatArray {
        val total = msEach * hz.size
        return tone(total, harmonics) { t -> hz[minOf(hz.lastIndex, (t * 1000 / msEach).toInt())] }
    }

    fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) { p.copyInto(out, at); at += p.size }
        return out
    }

    /** Adds the noise floor everywhere so "silence" isn't digital zero. */
    fun withFloor(signal: FloatArray): FloatArray {
        val floor = noise(signal.size * 1000 / SR + 1)
        return FloatArray(signal.size) { signal[it] + floor[it] }
    }
}
