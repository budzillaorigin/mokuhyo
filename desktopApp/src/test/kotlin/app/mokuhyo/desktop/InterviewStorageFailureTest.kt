package app.mokuhyo.desktop

import java.io.File
import kotlin.test.*

class InterviewStorageFailureTest {
    @Test fun failedAudioWriteKeepsAnswerAndAudioForRetry() {
        InterviewHarness().use { h ->
            h.show(); h.click("Start interview"); h.click("Start"); h.click("● Record")
            repeat(5) { h.pump() }; h.click("■ Stop")
            h.until("recognizer wasn't called") { h.transcription != null }
            val recordings = File(h.dir, "recordings")
            assertTrue(recordings.deleteRecursively())
            recordings.writeText("simulate a storage path failure")
            h.transcription!!("""[{"text":"Me llamo Ana."}]""", null)
            h.until("storage failure stranded the accepted answer") { "Retry question" in h.texts() }
            val row = h.app.conversations.list(h.app.learnerId, "es").single()
            assertEquals(1, h.app.conversations.stored(row).transcript.count { it.text == "Me llamo Ana." })
            assertTrue(recordings.delete()); assertTrue(recordings.mkdir())
            h.click("Retry question")
            h.until("retry did not save the retained audio") { h.app.conversations.recordings(row.id).isNotEmpty() }
            val updated = h.app.conversations.list(h.app.learnerId, "es").single()
            assertEquals(1, h.app.conversations.stored(updated).transcript.count { it.text == "Me llamo Ana." })
            assertTrue(File(h.dir, h.app.conversations.recordings(updated.id).single().path).length() > 44)
        }
    }
}
