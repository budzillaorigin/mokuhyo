package app.mokuhyo.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-00b: auto-stop after the silence that ends a turn; "no audio" on a dead line; blips aren't speech. */
class VadTest {
    private fun run(levels: List<Double>): List<Pair<Int, Vad.Event>> {
        val v = Vad()
        return levels.mapIndexedNotNull { i, l -> v.feed(l)?.let { i to it } }
    }

    @Test
    fun speechThenSilenceAutoStops() {
        val levels = List(5) { 0.003 } + List(20) { 0.15 } + List(20) { 0.004 }
        val ev = run(levels)
        assertEquals(Vad.Event.SPEECH_START, ev.first().second)
        val stop = ev.single { it.second == Vad.Event.AUTO_STOP }.first
        assertEquals(5 + 20 + 15 - 1, stop, "stops after 1.5 s (15 frames) of silence")
    }

    @Test
    fun deadMicrophoneReportsNoAudio() {
        val ev = run(List(60) { 0.0005 })
        assertEquals(listOf(39 to Vad.Event.NO_AUDIO), ev, "4 s of noise floor")
    }

    @Test
    fun shortBlipIsNotSpeechAndPausesDontStopEarly() {
        val v = Vad()
        (List(5) { 0.003 } + listOf(0.2) + List(5) { 0.003 }).forEach { v.feed(it) }
        assertFalse(v.heardSpeech, "a 100 ms click is not speech")
        // A 1-second pause in the middle of an answer doesn't end the turn.
        val ev = run(List(5) { 0.003 } + List(10) { 0.15 } + List(10) { 0.004 } + List(10) { 0.15 } + List(5) { 0.004 })
        assertTrue(ev.none { it.second == Vad.Event.AUTO_STOP })
    }

    @Test
    fun gainLiftsQuietRecordingsOnly() {
        val quiet = ShortArray(100) { if (it % 2 == 0) 1000 else -1000 }
        val g = Gain.normalize(quiet)
        assertEquals(10_000, g.maxOf { it.toInt() }, "capped at 10×")
        val loud = ShortArray(100) { if (it % 2 == 0) 30000 else -30000 }
        assertTrue(Gain.normalize(loud) === loud)
    }
}
