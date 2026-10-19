package app.mokuhyo.desktop

import app.mokuhyo.ai.*
import app.mokuhyo.opi.*
import kotlinx.coroutines.*
import kotlin.test.*

class InterviewCheckpointTest {
    @Test fun acceptedAnswerIsDurableWhileTheNextQuestionIsStillRunning() {
        InterviewHarness().use { h ->
            var calls = 0
            h.replace("gateway", AiGateway({ object : LanguageModel {
                override val id = "test"
                override val isLocal = true
                override suspend fun complete(request: CompletionRequest): CompletionResult {
                    if (++calls > 1) awaitCancellation()
                    return CompletionResult("""{"utterance":"¿Cómo está usted hoy?"}""", id, null)
                }
            } }))
            h.show(); h.click("Start interview"); h.click("Start")
            h.until("answer controls missing") { "Send typed answer" in h.texts() }
            h.type("Estoy bien."); h.click("Send typed answer")
            h.until("accepted answer was not checkpointed before inference") {
                h.app.conversations.list(h.app.learnerId, "es").any { row ->
                    h.app.conversations.stored(row).transcript.any { it.speaker == Speaker.LEARNER && it.text == "Estoy bien." }
                }
            }
            h.scene!!.close(); h.scene = null
            h.show()
            h.click("Resume interrupted interview")
            h.until("recovery lost the accepted answer") { "Estoy bien." in h.texts() }
        }
    }

    @Test fun failedPartnerReplyKeepsTheAcceptedLearnerAnswer() = runBlocking {
        val s = TopicSession("es", OpiProfile("es", "usted"), Topic("test", "daily_life", "Names", "¿Cómo se llama?"), AiGateway({ null }))
        assertNull(s.say("Me llamo Ana."))
        assertEquals(Turn(Speaker.LEARNER, "Me llamo Ana."), s.transcript.last())
    }
}
