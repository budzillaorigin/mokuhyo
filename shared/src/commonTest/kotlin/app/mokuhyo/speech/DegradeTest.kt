package app.mokuhyo.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-06 gate: DSP unit tests and A/B fixtures for the degraded-audio chain. */
class DegradeTest {
    private fun tone(hz: Double, seconds: Double = 1.0, amp: Double = 0.5) =
        DoubleArray((Degrade.RATE * seconds).toInt()) { amp * sin(2 * PI * hz * it / Degrade.RATE) }

    private fun pcm(x: DoubleArray) = ShortArray(x.size) { (x[it] * 32767).toInt().toShort() }

    @Test
    fun bandPassKeepsSpeechBandAndCutsTheRest() {
        fun gain(hz: Double): Double {
            val x = tone(hz)
            val y = Degrade.bandPass(x, 300.0, 3400.0)
            return Degrade.rms(y.copyOfRange(4000, y.size)) / Degrade.rms(x.copyOfRange(4000, x.size))
        }
        assertTrue(gain(1000.0) > 0.9, "1 kHz passes: ${gain(1000.0)}")
        assertTrue(gain(60.0) < 0.1, "60 Hz is cut: ${gain(60.0)}")
        assertTrue(gain(7000.0) < 0.35, "7 kHz is cut: ${gain(7000.0)}")
    }

    @Test
    fun noiseIsMixedAtTheTargetSnr() {
        listOf(20.0, 10.0, 0.0).forEach { snr ->
            val s = tone(800.0)
            val clean = s.copyOf()
            val noise = Degrade.bed(Degrade.Bed.PINK, s.size, Random(3))
            Degrade.mixAtSnr(s, noise, snr)
            val added = DoubleArray(s.size) { s[it] - clean[it] }
            assertTrue(abs(Degrade.snrDb(clean, added) - snr) < 0.01, "snr $snr")
        }
        assertEquals(20.0, Degrade.snrFor(0.0))
        assertEquals(0.0, Degrade.snrFor(1.0))
    }

    @Test
    fun chainIsDeterministicSameLengthAndBounded() {
        val speech = pcm(tone(500.0, 2.0).also { x -> for (i in x.indices) x[i] += 0.3 * sin(2 * PI * 1700 * i / Degrade.RATE) })
        Degrade.Preset.entries.forEach { p ->
            val a = Degrade.apply(speech, p, 0.7, seed = 5)
            val b = Degrade.apply(speech, p, 0.7, seed = 5)
            assertEquals(speech.size, a.size, p.name)
            assertContentEquals(a, b, "${p.name} deterministic")
            assertTrue(a.any { it != 0.toShort() } && !a.contentEquals(speech), p.name)
        }
    }

    @Test
    fun harderMeansNoisier() {
        val speech = pcm(tone(700.0, 2.0))
        fun residual(d: Double): Double {
            val y = Degrade.apply(speech, Degrade.Preset.TELEPHONE, d, seed = 9)
            val ref = Degrade.apply(speech, Degrade.Preset.TELEPHONE, 0.0, seed = 9)
            return Degrade.rms(DoubleArray(y.size) { (y[it] - ref[it]) / 32768.0 })
        }
        assertTrue(residual(1.0) > residual(0.3))
    }

    @Test
    fun testModeIsAlwaysClean() {
        val speech = pcm(tone(440.0))
        Degrade.Preset.entries.forEach { p -> assertContentEquals(speech, Degrade.apply(speech, p, 1.0, testMode = true)) }
    }

    @Test
    fun crossTalkAddsTheOtherVoiceUnderneath() {
        val a = pcm(tone(500.0))
        val b = pcm(tone(1500.0, amp = 0.4))
        val with = Degrade.apply(a, Degrade.Preset.CROWD, 0.0, seed = 2, crossTalk = b)
        val without = Degrade.apply(a, Degrade.Preset.CROWD, 0.0, seed = 2)
        assertTrue(!with.contentEquals(without))
    }
}
