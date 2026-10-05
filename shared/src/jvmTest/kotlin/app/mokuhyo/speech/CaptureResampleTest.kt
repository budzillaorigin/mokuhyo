package app.mokuhyo.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-00b: capture at the device's native rate (48/44.1 kHz, mono or stereo) resamples to 16 kHz for Whisper. */
class CaptureResampleTest {
    private fun tone(rate: Int, channels: Int, hz: Double = 440.0, seconds: Double = 1.0): ByteArray {
        val n = (rate * seconds).toInt()
        val out = ByteArray(n * channels * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * hz * i / rate) * 12000).toInt()
            for (c in 0 until channels) {
                val k = (i * channels + c) * 2
                out[k] = (v and 0xFF).toByte()
                out[k + 1] = (v shr 8).toByte()
            }
        }
        return out
    }

    private fun zeroCrossings(s: ShortArray) = (1 until s.size).count { (s[it - 1] < 0) != (s[it] < 0) }

    @Test
    fun nativeRatesBecome16kMonoWithThePitchKept() {
        for ((rate, ch) in listOf(48_000 to 1, 44_100 to 2, 16_000 to 1)) {
            val pcm = AudioIO.toMono16k(tone(rate, ch), rate, ch)
            assertTrue(abs(pcm.size - 16_000) <= 2, "$rate/$ch: ${pcm.size} samples")
            assertTrue(abs(zeroCrossings(pcm) - 880) <= 6, "$rate/$ch: 440 Hz kept (${zeroCrossings(pcm)} crossings)")
            assertTrue(pcm.maxOf { it.toInt() } in 10_000..12_100, "$rate/$ch: level kept")
        }
        assertEquals(listOf(48_000f, 44_100f, 16_000f), AudioIO.CAPTURE_CANDIDATES.map { it.sampleRate }.distinct())
    }
}
