package app.mokuhyo.speech

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * QC 2026-10-30 V7: patience sets the VAD endpoint. Synthetic RMS frames (100 ms) of a hesitant answer: 1 s of speech,
 * a 2 s thinking pause, 1 s more speech, then the 3 s of silence that really ends the turn.
 */
class CapturePatienceTest {
    private val hesitant = List(5) { 0.002 } + List(10) { 0.1 } + List(20) { 0.002 } + List(10) { 0.1 } + List(30) { 0.002 }
    private val thinkingPauseEnds = 5 + 10 + 20

    /** The frame index at which the VAD ended the turn, or null. */
    private fun endsAt(vad: Vad): Int? = hesitant.indexOfFirst { vad.feed(it) == Vad.Event.AUTO_STOP }.takeIf { it >= 0 }

    @Test
    fun aLongPatienceWaitsThroughAThinkingPauseAndAShortOneEndsThere() {
        val short = endsAt(CapturePatience.SHORT.vad())!!
        val long = endsAt(CapturePatience.LONG.vad())!!
        assertEquals(true, short < thinkingPauseEnds, "short patience ends the turn in the thinking pause (frame $short)")
        assertEquals(true, long > thinkingPauseEnds + 10, "long patience keeps the second half of the answer (frame $long)")
    }

    @Test
    fun personaPatienceChangesTheEndpointAndTheDefaultIsUnchanged() {
        assertEquals(CapturePatience.SHORT, CapturePatience.forPersona(1))
        assertEquals(CapturePatience.NORMAL, CapturePatience.forPersona(3))
        assertEquals(CapturePatience.VERY_LONG, CapturePatience.forPersona(5))
        assertEquals(Vad().silenceStopMs, CapturePatience.NORMAL.silenceStopMs, "normal is today's 1.5 s")
        assertEquals(1_500, Vad().silenceStopMs, "the default capture (manual mode, tests) keeps its endpoint")
    }
}
