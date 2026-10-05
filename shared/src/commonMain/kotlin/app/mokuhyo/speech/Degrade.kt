package app.mokuhyo.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The degraded-audio chain for listening practice (BRIEF_PHASE8 N-06): band-limiting, a noise bed at a target
 * signal-to-noise ratio, compression and clipping, optional cross-talk. 16 kHz mono PCM in and out, same length.
 * Practice only: [apply] with `testMode = true` returns the clean audio unchanged.
 *
 * Noise beds are synthesized here (no owner recordings were supplied; the UI labels them "synthesized"):
 * pink noise, engine/turbine tones, 50 Hz mains hum with harmonics, amplitude-modulated babble-band noise.
 */
object Degrade {
    const val RATE = 16_000

    enum class Preset(val title: String, val lowHz: Double, val highHz: Double, val bed: Bed, val compression: Double) {
        TELEPHONE("Telephone", 300.0, 3400.0, Bed.PINK, 0.3),
        RADIO("VHF/UHF radio", 350.0, 2800.0, Bed.STATIC, 0.7),
        FLIGHTLINE("Flightline", 200.0, 6000.0, Bed.TURBINE, 0.5),
        GENERATOR("Generator room", 150.0, 5000.0, Bed.HUM, 0.4),
        CROWD("Crowd", 150.0, 6500.0, Bed.BABBLE, 0.2),
        VEHICLE("Vehicle interior", 120.0, 4500.0, Bed.ENGINE, 0.3),
    }

    enum class Bed { PINK, STATIC, TURBINE, HUM, BABBLE, ENGINE }

    /** [difficulty] 0..1 maps to SNR +20 dB … 0 dB and stronger compression. */
    fun snrFor(difficulty: Double): Double = 20.0 - 20.0 * difficulty.coerceIn(0.0, 1.0)

    fun apply(
        pcm: ShortArray, preset: Preset, difficulty: Double, seed: Int = 1, crossTalk: ShortArray? = null, testMode: Boolean = false,
    ): ShortArray {
        if (testMode || pcm.isEmpty()) return pcm
        val d = difficulty.coerceIn(0.0, 1.0)
        var x = DoubleArray(pcm.size) { pcm[it] / 32768.0 }
        x = bandPass(x, preset.lowHz, preset.highHz)
        if (crossTalk != null && crossTalk.isNotEmpty()) {
            val other = bandPass(DoubleArray(pcm.size) { crossTalk[(it + RATE / 3) % crossTalk.size] / 32768.0 }, preset.lowHz, preset.highHz)
            val gain = rms(x) / (rms(other).coerceAtLeast(1e-9)) * 10.0.pow(-12.0 / 20) // cross-talk 12 dB under the voice
            for (i in x.indices) x[i] += other[i] * gain
        }
        val noise = bed(preset.bed, pcm.size, Random(seed))
        mixAtSnr(x, noise, snrFor(d))
        compress(x, preset.compression + 0.3 * d)
        if (preset == Preset.RADIO) clip(x, 0.6 - 0.25 * d)
        return ShortArray(x.size) { (x[it].coerceIn(-1.0, 1.0) * 32767).toInt().toShort() }
    }

    fun rms(x: DoubleArray): Double = sqrt(x.sumOf { it * it } / x.size.coerceAtLeast(1))

    /** Adds [noise] scaled so 20·log10(rms(signal)/rms(noise)) = [snrDb]. */
    fun mixAtSnr(signal: DoubleArray, noise: DoubleArray, snrDb: Double) {
        val s = rms(signal)
        val n = rms(noise)
        if (s == 0.0 || n == 0.0) return
        val g = s / n / 10.0.pow(snrDb / 20)
        for (i in signal.indices) signal[i] += noise[i] * g
    }

    fun snrDb(signal: DoubleArray, noise: DoubleArray): Double = 20 * log10(rms(signal) / rms(noise))

    /** Second-order (RBJ biquad) high-pass at [low] then low-pass at [high]. */
    fun bandPass(x: DoubleArray, low: Double, high: Double): DoubleArray = biquad(biquad(x, low, highPass = true), high, highPass = false)

    private fun biquad(x: DoubleArray, f: Double, highPass: Boolean, q: Double = 0.7071): DoubleArray {
        val w = 2 * PI * f / RATE
        val alpha = sin(w) / (2 * q)
        val cos = kotlin.math.cos(w)
        val (b0, b1, b2) = if (highPass) Triple((1 + cos) / 2, -(1 + cos), (1 + cos) / 2) else Triple((1 - cos) / 2, 1 - cos, (1 - cos) / 2)
        val a0 = 1 + alpha
        val a1 = -2 * cos
        val a2 = 1 - alpha
        val y = DoubleArray(x.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (i in x.indices) {
            val v = (b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v
            y[i] = v
        }
        return y
    }

    /** Soft compression (tanh drive), normalized back to the input peak. */
    fun compress(x: DoubleArray, amount: Double) {
        val drive = 1 + 4 * amount.coerceIn(0.0, 1.0)
        val peak = x.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: return
        val outPeak = tanh(drive)
        for (i in x.indices) x[i] = tanh(drive * x[i] / peak) / outPeak * peak
    }

    fun clip(x: DoubleArray, level: Double) {
        val peak = x.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: return
        val t = peak * level
        for (i in x.indices) x[i] = x[i].coerceIn(-t, t) / level
    }

    fun bed(kind: Bed, n: Int, r: Random): DoubleArray = when (kind) {
        Bed.PINK -> pink(n, r)
        Bed.STATIC -> DoubleArray(n) { (r.nextDouble() * 2 - 1) * (if (r.nextInt(400) == 0) 3.0 else 1.0) } // crackle on white noise
        Bed.TURBINE -> pink(n, r).also { p -> for (i in 0 until n) p[i] += 0.6 * sin(2 * PI * 3150 * i / RATE) + 0.3 * sin(2 * PI * 140 * i / RATE) }
        Bed.HUM -> DoubleArray(n) { i -> (1..5).sumOf { h -> sin(2 * PI * 50 * h * i / RATE) / h } }.also { h -> pink(n, r).forEachIndexed { i, v -> h[i] += 0.3 * v } }
        Bed.BABBLE -> bandPass(pink(n, r), 300.0, 3000.0).also { b -> for (i in 0 until n) b[i] *= 0.6 + 0.4 * sin(2 * PI * 4.0 * i / RATE + r.nextDouble() * 0.01) }
        Bed.ENGINE -> DoubleArray(n) { i -> sin(2 * PI * 32 * i / RATE) + 0.5 * sin(2 * PI * 64 * i / RATE) }.also { e -> pink(n, r).forEachIndexed { i, v -> e[i] += 0.5 * v } }
    }

    /** Pink noise (Paul Kellet's economy filter over white noise). */
    private fun pink(n: Int, r: Random): DoubleArray {
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        return DoubleArray(n) {
            val w = r.nextDouble() * 2 - 1
            b0 = 0.99765 * b0 + w * 0.0990460
            b1 = 0.96300 * b1 + w * 0.2965164
            b2 = 0.57000 * b2 + w * 1.0526913
            b0 + b1 + b2 + w * 0.1848
        }
    }
}
