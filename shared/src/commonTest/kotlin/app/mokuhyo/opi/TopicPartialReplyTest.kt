package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.CompletionRequest
import app.mokuhyo.ai.CompletionResult
import app.mokuhyo.ai.LanguageModel
import app.mokuhyo.speech.Delivery
import app.mokuhyo.speech.UtteranceDelivery
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** QC 2026-10-30 V10: a topic reply that stopped after part of it was spoken is kept, with what was heard. */
class TopicPartialReplyTest {
    private val model = object : LanguageModel {
        override val id = "test"
        override val isLocal = true
        override suspend fun complete(request: CompletionRequest): CompletionResult {
            // The second sentence switches to English and fails its check after the first went to speech.
            val pieces = listOf("{\"reply\":\"Qué interesante. ", "And where do you work now? ", "¿Le gusta?\"}")
            pieces.forEach { request.onToken?.invoke(it) }
            return CompletionResult(pieces.joinToString(""), id, null)
        }
    }

    @Test
    fun aPartlySpokenReplyKeepsItsExchange() = runTest {
        val s = TopicSession("es", OpiProfile("es", "usted"), Topic("t", "work", "Trabajo", "¿Dónde trabaja?"), AiGateway({ model }))
        val spoken = ArrayList<String>()
        val ex = assertNotNull(s.say("Trabajo en una oficina.", speak = { spoken += it }), "the exchange is not lost")
        assertEquals(listOf("Qué interesante."), spoken)
        assertTrue(ex.partial)
        assertEquals("Qué interesante.", ex.reply)
        assertEquals(1, s.exchanges.size)
        s.delivered(0, "", listOf(UtteranceDelivery("0:0", "Qué interesante.", Delivery.INTERRUPTED.wire)))
        assertEquals(Delivery.INTERRUPTED.wire, s.exchanges.single().delivery!!.single().state, "what was heard is recorded")
    }
}
