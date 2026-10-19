package app.mokuhyo.speech

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OggOpusTest {
    @Test
    fun roundTripKeepsLengthAndSignal() {
        val seconds = 3
        val pcm = ShortArray(16_000 * seconds) { i -> (8000 * sin(2 * PI * 440 * i / 16_000)).toInt().toShort() }
        val ogg = OggOpus.encode(pcm)
        assertTrue(OggOpus.isOggOpus(ogg))
        assertTrue(ogg.size < pcm.size * 2 / 6, "compressed ${ogg.size} bytes vs ${pcm.size * 2} raw")
        val back = OggOpus.decode(ogg)
        assertTrue(abs(back.size - pcm.size) <= 640, "length ${back.size} vs ${pcm.size}")
        val energy = back.drop(4000).take(16_000).map { abs(it.toInt()) }.average()
        assertTrue(energy > 2000, "decoded signal too weak: $energy")
    }

    /** QC 2026-10-19: clips can keep 24 kHz for playback; the rate travels in the header and old 16 kHz clips still decode. */
    @Test
    fun twentyFourKilohertzRoundTripKeepsItsRate() {
        val rate = 24_000
        val pcm = ShortArray(rate * 2) { i -> (8000 * sin(2 * PI * 6000 * i / rate)).toInt().toShort() }
        val ogg = OggOpus.encode(pcm, bitrate = 32_000, sampleRate = rate)
        assertEquals(rate, OggOpus.rateOf(ogg))
        val (back, backRate) = OggOpus.decodeWithRate(ogg)
        assertEquals(rate, backRate)
        assertTrue(abs(back.size - pcm.size) <= 960, "length ${back.size} vs ${pcm.size}")
        assertEquals(16_000, OggOpus.rateOf(OggOpus.encode(ShortArray(16_000))))
        val wav = AudioIO.clipWav(ogg)
        assertEquals(rate, AudioIO.toMonoPcm(wav).second)
    }

    @Test
    fun emptyInputIsAValidStream() {
        val ogg = OggOpus.encode(ShortArray(0))
        assertEquals(0, OggOpus.decode(ogg).size)
    }
}
