package app.mokuhyo.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.mokuhyo.settings.Settings

class InterviewCaptureTest {
    @Test fun confirmationRetainsTextAndCancelCanRetryTheSameAudio() {
        InterviewHarness().use { h ->
            h.app.settings.put(Settings.Key.CONFIRM_TRANSCRIPT, "true")
            h.show(); h.click("Start interview"); h.click("Start"); h.click("● Record")
            repeat(5) { h.pump() }
            h.click("■ Stop")
            h.until("recognizer wasn't called") { h.transcription != null }
            val old = h.transcription!!
            h.click("Cancel transcription")
            h.until("native ASR wasn't cancelled") { h.cancelled }
            h.transcription = null
            h.click("Retry transcription")
            h.until("retry wasn't started") { h.transcription != null }
            old("""[{"text":"Stale text"}]""", null)
            h.transcription!!("""[{"text":"Me llamo Ana."}]""", null)
            h.until("confirmation wasn't shown") { "What I heard" in h.texts() }
            assertFalse("Stale text" in h.texts())
            h.click("Send")
            h.until("confirmed transcript wasn't submitted") { "Me llamo Ana." in h.texts() }
        }
    }

    @Test fun navigationCancelsOutstandingRecognition() {
        InterviewHarness().use { h ->
            h.show(); h.click("Start interview"); h.click("Start"); h.click("● Record")
            repeat(5) { h.pump() }
            h.click("■ Stop")
            h.until("recognizer wasn't called") { h.transcription != null }
            h.scene!!.close(); h.scene = null
            assertTrue(h.cancelled)
            h.transcription!!("""[{"text":"Late result"}]""", null)
        }
    }
    @Test fun transcriptionSurvivesTheParentsBusyState() {
        InterviewHarness().use { h ->
            h.show()
            h.click("Start interview")
            h.click("Start")
            h.click("● Record")
            repeat(5) { h.pump() }
            h.click("■ Stop")
            h.until("recognizer wasn't called") { h.transcription != null }
            repeat(10) { h.pump() }
            assertFalse(h.cancelled, "Changing the parent to busy must not cancel the recording's recognizer")
            h.transcription!!("""[{"t0":0,"t1":100,"text":"Me llamo Ana."}]""", null)
            h.until("transcript wasn't submitted") { "Me llamo Ana." in h.texts() }
        }
    }
}
