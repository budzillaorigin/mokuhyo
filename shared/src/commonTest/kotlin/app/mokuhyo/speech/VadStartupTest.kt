package app.mokuhyo.speech

import kotlin.test.*

class VadStartupTest {
    @Test fun speechInFrameOneIsNotLearnedAsTheNoiseFloor() {
        val vad = Vad()
        val events = (List(40) { 0.15 } + List(15) { 0.003 }).mapNotNull { vad.feed(it) }
        assertTrue(vad.heardSpeech)
        assertEquals(listOf(Vad.Event.SPEECH_START, Vad.Event.AUTO_STOP), events)
    }
    @Test fun quietFanAndAnInitialClickStillReportNoSpeech() {
        val vad = Vad()
        val events = (listOf(0.2) + List(39) { 0.003 }).mapNotNull { vad.feed(it) }
        assertFalse(vad.heardSpeech)
        assertEquals(listOf(Vad.Event.NO_AUDIO), events)
    }
}
