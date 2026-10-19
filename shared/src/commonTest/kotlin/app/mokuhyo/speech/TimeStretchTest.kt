package app.mokuhyo.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** QC 2026-10-19: slow playback keeps the voice's pitch (it used to drop ~3.9 semitones at 0.8×). */
class TimeStretchTest {
    private val rate = 22_050

    private fun tone(hz: Double, seconds: Double) = ShortArray((rate * seconds).toInt()) { i -> (8000 * sin(2 * PI * hz * i / rate)).toInt().toShort() }

    /** Frequency from upward zero crossings over the middle of the signal. */
    private fun frequency(x: ShortArray): Double {
        val from = x.size / 4
        val to = x.size * 3 / 4
        var crossings = 0
        for (i in from + 1 until to) if (x[i - 1] < 0 && x[i] >= 0) crossings++
        return crossings * rate.toDouble() / (to - from)
    }

    @Test
    fun slowerKeepsPitchAndStretchesLength() {
        val input = tone(220.0, 2.0)
        for (speed in listOf(0.8, 0.9)) {
            val out = TimeStretch.stretch(input, rate, speed)
            assertEquals((input.size / speed).toInt(), out.size, "length at $speed×")
            val f = frequency(out)
            assertTrue(abs(f - 220.0) < 220.0 * 0.02, "pitch at $speed× is $f Hz, expected 220")
            val level = out.copyOfRange(out.size / 4, out.size * 3 / 4).map { abs(it.toInt()) }.average()
            assertTrue(level > 4000, "stretched signal too weak: $level")
        }
    }

    @Test
    fun normalSpeedIsUntouched() {
        val input = tone(300.0, 0.5)
        assertSame(input, TimeStretch.stretch(input, rate, 1.0))
    }

    @Test
    fun shortAndEmptyInputsDontCrash() {
        assertEquals(0, TimeStretch.stretch(ShortArray(0), rate, 0.8).size)
        assertEquals(125, TimeStretch.stretch(ShortArray(100) { 1000 }, rate, 0.8).size)
    }
}
