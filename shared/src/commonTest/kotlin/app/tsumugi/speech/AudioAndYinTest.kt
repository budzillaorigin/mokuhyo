package app.tsumugi.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioAndYinTest {
    private fun medianF0(signal: FloatArray): Float = Yin.track(signal).medianHz

    private fun assertWithinPercent(expected: Double, actual: Float, percent: Double) {
        assertTrue(abs(actual - expected) / expected * 100 <= percent, "expected $expected Hz ±$percent%, got $actual")
    }

    @Test
    fun yinFindsPureSinesWithinOnePercent() {
        for (hz in listOf(80.0, 110.0, 150.0, 220.0, 300.0, 440.0)) {
            val signal = Signals.steady(500, hz)
            val track = Yin.track(signal)
            assertTrue(track.voicedCount > track.size * 0.8, "$hz Hz mostly voiced: ${track.voicedCount}/${track.size}")
            println("YIN sine $hz Hz → ${track.medianHz} Hz (${abs(track.medianHz - hz) / hz * 100}% error)")
            assertWithinPercent(hz, track.medianHz, 1.0)
        }
    }

    @Test
    fun yinFindsHarmonicTonesWithinOnePercent() {
        for (hz in listOf(100.0, 130.0, 200.0, 260.0)) {
            assertWithinPercent(hz, medianF0(Signals.steady(500, hz, harmonics = 6)), 1.0)
        }
    }

    @Test
    fun yinFollowsRisingAndFallingGlides() {
        val rising = Yin.track(Signals.tone(1000, harmonics = 3) { t -> 120 + 120 * t })
        val falling = Yin.track(Signals.tone(1000, harmonics = 3) { t -> 240 - 120 * t })
        // Frame 50 ≈ window centred near t = 0.52 s.
        assertWithinPercent(120 + 120 * 0.52, rising.f0[50], 3.0)
        assertWithinPercent(240 - 120 * 0.52, falling.f0[50], 3.0)
        val riseFirst = rising.f0.slice(10..30).filter { !it.isNaN() }.average()
        val riseLast = rising.f0.slice(65..85).filter { !it.isNaN() }.average()
        assertTrue(riseLast > riseFirst * 1.3, "rising: $riseFirst → $riseLast")
        val fallFirst = falling.f0.slice(10..30).filter { !it.isNaN() }.average()
        val fallLast = falling.f0.slice(65..85).filter { !it.isNaN() }.average()
        assertTrue(fallLast < fallFirst / 1.3, "falling: $fallFirst → $fallLast")
    }

    @Test
    fun whiteNoiseIsUnvoiced() {
        val track = Yin.track(Signals.noise(1000, amplitude = 0.3f, seed = 7))
        assertTrue(track.voicedCount < track.size * 0.1, "noise voiced frames: ${track.voicedCount}/${track.size}")
    }

    @Test
    fun semitonesAreRelativeToTheSpeakersMedian() {
        val track = Yin.track(Signals.concat(Signals.steady(400, 200.0), Signals.steady(400, 100.0), Signals.steady(400, 200.0)))
        val semis = track.semitones().filter { !it.isNaN() }
        assertTrue(semis.any { abs(it) < 0.2f }, "median voice sits at 0 st")
        assertTrue(semis.any { abs(it + 12f) < 0.3f }, "an octave down is −12 st")
    }

    @Test
    fun silenceSplitsSpeechIntoSegmentsAndLongGapsArePauses() {
        val signal = Signals.withFloor(
            Signals.concat(
                Signals.silence(500), Signals.steady(1000, 150.0, 4),
                Signals.silence(500), Signals.steady(800, 180.0, 4),
                Signals.silence(200), Signals.steady(600, 160.0, 4), Signals.silence(500),
            ),
        )
        val segments = VoiceActivity.segments(signal)
        assertEquals(3, segments.size, "segments: $segments")
        assertTrue(segments[2].startMs - segments[1].endMs < 250, "the 200 ms gap stays short after hangover")
        assertTrue(abs(segments[0].startMs - 500) <= 40, "first onset ${segments[0].startMs}")
        assertTrue(abs(segments[0].durationMs - 1000) <= 120, "first length ${segments[0].durationMs}")
        val pauses = VoiceActivity.pauses(segments)
        assertEquals(1, pauses.size, "only the 500 ms gap is a pause, not the 200 ms one: $pauses")
        assertTrue(pauses[0].durationMs in 350..520, "pause length ${pauses[0].durationMs}")
    }

    @Test
    fun pcm16ConversionAndResampling() {
        assertEquals(listOf(0f, 0.5f, -1f), Audio.pcm16ToFloat(shortArrayOf(0, 16384, -32768)).toList())
        assertEquals(listOf(0.5f), Audio.pcm16LeToFloat(byteArrayOf(0x00, 0x40)).toList())
        val at44k = FloatArray(44100) { (0.3 * sin(2 * PI * 440 * it / 44100.0)).toFloat() }
        val resampled = Audio.resample(at44k, 44100)
        assertTrue(abs(resampled.size - 16000) <= 1)
        assertWithinPercent(440.0, medianF0(resampled), 1.0)
    }
}
