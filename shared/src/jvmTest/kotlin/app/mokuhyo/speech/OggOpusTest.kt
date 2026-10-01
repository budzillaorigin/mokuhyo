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

    @Test
    fun emptyInputIsAValidStream() {
        val ogg = OggOpus.encode(ShortArray(0))
        assertEquals(0, OggOpus.decode(ogg).size)
    }
}
