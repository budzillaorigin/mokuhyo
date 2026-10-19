package app.mokuhyo.desktop

import kotlin.test.*
import kotlin.time.TimeSource

class InterviewDeviceTest {
    @Test fun slowDeviceOpenDoesNotBlockTheUiAndNavigationClosesIt() {
        InterviewHarness().use { h ->
            h.show(); h.click("Start interview"); h.click("Start")
            InterviewMicrophone.openDelayMs = 1_000
            val closed = InterviewMicrophone.closed
            val opening = InterviewMicrophone.opening
            val start = TimeSource.Monotonic.markNow()
            h.click("● Record")
            assertTrue(start.elapsedNow().inWholeMilliseconds < 700, "device open blocked the UI")
            h.until("device open was not attempted") { InterviewMicrophone.opening > opening }
            h.scene!!.close(); h.scene = null
            val deadline = System.nanoTime() + 4_000_000_000L
            while (InterviewMicrophone.closed == closed && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(InterviewMicrophone.closed > closed, "navigation during open leaked the line")
        }
    }
    @Test fun slowDeviceStopDoesNotBlockTheUiOrLoseTheAudio() {
        InterviewHarness().use { h ->
            h.show(); h.click("Start interview"); h.click("Start"); h.click("● Record")
            repeat(5) { h.pump() }
            InterviewMicrophone.stopDelayMs = 1_000
            val start = TimeSource.Monotonic.markNow()
            h.click("■ Stop")
            assertTrue(start.elapsedNow().inWholeMilliseconds < 700, "device stop/resampling blocked the UI")
            h.until("audio was not passed to ASR after device stop") { h.transcription != null }
        }
    }
}
