package app.mokuhyo.speech

import kotlin.math.PI
import kotlin.math.cos

/**
 * Slows speech down without lowering its pitch (WSOLA: waveform-similarity overlap-add). Playing at a lower sample
 * rate instead drops the pitch about 3.9 semitones at 0.8×, which makes voices sound unnatural (QC 2026-10-19).
 *
 * 40 ms Hann frames at 50 % overlap; each next frame is taken from within ±10 ms of its nominal input position,
 * wherever it lines up best with the natural continuation of the previous frame, so periods aren't cut mid-cycle.
 */
object TimeStretch {
    /** Mono PCM16 [samples] at [sampleRate] played at [speed] (0.5–2.0; 1.0 returns the input). */
    fun stretch(samples: ShortArray, sampleRate: Int, speed: Double): ShortArray {
        require(speed in 0.5..2.0) { "speed $speed outside 0.5–2.0" }
        if (speed == 1.0 || samples.isEmpty()) return samples
        val n = ((sampleRate * 0.04).toInt() / 2 * 2).coerceAtLeast(16)
        val hop = n / 2
        val tolerance = n / 4
        val x = FloatArray(samples.size) { samples[it].toFloat() }
        val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }
        val outLen = (samples.size / speed).toInt()
        val out = FloatArray(outLen + n)
        var previous = 0
        var k = 0
        while (true) {
            val outPos = k * hop
            if (outPos >= outLen) break
            val nominal = (outPos * speed).toInt()
            if (nominal >= x.size) break
            val pos = if (k == 0) 0 else bestMatch(x, previous + hop, nominal, tolerance, hop, n)
            for (i in 0 until n) {
                val src = pos + i
                if (src >= x.size) break
                // The first frame has no predecessor to overlap with: don't fade it in.
                val w = if (k == 0 && i < hop) 1f else window[i]
                out[outPos + i] += x[src] * w
            }
            previous = pos
            k++
        }
        return ShortArray(outLen) { out[it].toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** The start within [tolerance] of [nominal] whose first [hop] samples best match those at [natural]. */
    private fun bestMatch(x: FloatArray, natural: Int, nominal: Int, tolerance: Int, hop: Int, n: Int): Int {
        val last = x.size - n
        if (last <= 0) return nominal.coerceIn(0, (x.size - 1).coerceAtLeast(0))
        val from = (nominal - tolerance).coerceIn(0, last)
        val to = (nominal + tolerance).coerceIn(0, last)
        if (natural + hop > x.size) return nominal.coerceIn(from, to)
        var best = nominal.coerceIn(from, to)
        var bestScore = Double.NEGATIVE_INFINITY
        for (p in from..to) {
            var score = 0.0
            var i = 0
            while (i < hop) { // every other sample: plenty for speech, half the cost
                score += x[natural + i] * x[p + i]
                i += 2
            }
            if (score > bestScore) { bestScore = score; best = p }
        }
        return best
    }
}
