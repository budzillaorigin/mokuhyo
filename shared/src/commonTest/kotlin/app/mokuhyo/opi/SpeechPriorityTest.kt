package app.mokuhyo.opi

import app.mokuhyo.ai.*
import app.mokuhyo.exam.IlrLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SpeechPriorityTest {
    @Test fun liveReplyReturnsBeforeSlowCritique() = replyFirst(CorrectionsMode.LIVE)
    @Test fun afterActionReplyReturnsBeforeSlowCritique() = replyFirst(CorrectionsMode.AFTER_ACTION)

    private fun replyFirst(mode: CorrectionsMode) = runTest {
        var calls = 0
        val model = object : LanguageModel {
            override val id = "test"
            override val isLocal = true
            override suspend fun complete(request: CompletionRequest): CompletionResult {
                if (++calls > 1) awaitCancellation()
                return CompletionResult("""{"reply":"¿Y luego?","reply_english":"And then?"}""", id, null)
            }
        }
        val s = TopicSession("es", OpiProfile("es", "usted"), Topic("t", "daily_life", "Names", "¿Cómo se llama?"), AiGateway({ model }), mode = mode)
        val reply = withTimeoutOrNull(100) { s.say("Me llamo Ana.") }
        assertEquals("¿Y luego?", assertNotNull(reply, "speech must be available even while critique is blocked").reply)
        assertEquals(1, calls, "critique is scheduled separately")
    }

    @Test fun foregroundPreemptsRunningCritiqueAndWaitsForItsCancellationCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        var cleaned = false
        val model = object : LanguageModel {
            override val id = "test"
            override val isLocal = true
            override suspend fun complete(request: CompletionRequest): CompletionResult {
                if (request.messages.any { "corrected" in it.content }) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { withContext(NonCancellable) { delay(10); cleaned = true } }
                }
                assertTrue(cleaned, "foreground must not issue native work until background cancellation is complete")
                return CompletionResult("""{"reply":"¿Y luego?","reply_english":"And then?"}""", id, null)
            }
        }
        val gateway = AiGateway({ model })
        val bg = backgroundScope.async { gateway.run(TurnFeedback(), TurnFeedback.Input("es", "usted", "¿Su nombre?", "Ana.")) }
        started.await()
        val reply = gateway.run(PartnerReply(), TopicTurn.Input("es", "usted", "Names", "daily_life", IlrLevel.L1, listOf(Turn(Speaker.LEARNER, "Ana."))))
        assertIs<AiResult.Ok<*>>(reply)
        assertTrue(bg.isCompleted, "background must have been stopped, without cancelling the foreground")
        assertIs<AiResult.Unavailable>(bg.await())
    }
}
