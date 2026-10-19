package app.mokuhyo.desktop

import app.mokuhyo.ai.*
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test

class InterviewCancelTest {
    @Test fun cancellingTheFirstQuestionOffersRetryAndEnd() = checkCancellation(false)
    @Test fun cancellingAfterAnAnswerOffersRetryAndEnd() = checkCancellation(true)

    private fun checkCancellation(answerFirst: Boolean) {
        InterviewHarness().use { h ->
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            h.replace("gateway", AiGateway({ object : LanguageModel {
                override val id = "test"
                override val isLocal = true
                override suspend fun complete(request: CompletionRequest): CompletionResult {
                    calls++
                    if (!answerFirst || calls > 1) gate.await()
                    return CompletionResult("""{"utterance":"¿Cómo está usted hoy?","english":"How are you today?"}""", id, null)
                }
            } }))
            h.show(); h.click("Start interview"); h.click("Start")
            if (answerFirst) {
                h.until("answer controls missing") { "Send typed answer" in h.texts() }
                h.type("Estoy bien."); h.click("Send typed answer")
            }
            h.click("Cancel")
            h.until("Cancel stranded the interview") { "Retry question" in h.texts() && "End interview" in h.texts() }
            gate.complete(Unit)
            h.click("Retry question")
            h.until("Retry did not restore the answer controls") { "Send typed answer" in h.texts() }
        }
    }
}
