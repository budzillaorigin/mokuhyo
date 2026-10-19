package app.mokuhyo.opi

import app.mokuhyo.ai.*
import app.mokuhyo.exam.IlrLevel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** BRIEF_INTERVIEW_SPEED N3: the live reply carries only what is spoken; the English gloss comes later, in the background. */
class LiveSchemaTest {
    private fun keys(s: JsonSchema) = (s as JsonSchema.Obj).properties.map { it.first }

    @Test
    fun liveSchemasAreTheSpokenUtteranceOnly() {
        assertEquals(listOf("utterance"), keys(OpiInterviewerTurn().schema))
        assertEquals(listOf("reply"), keys(PartnerReply().schema))
        val opi = OpiInterviewerTurn().messages(OpiInterviewerTurn.Input("es", "usted", OpiPhase.WARMUP, IlrLevel.L1)).joinToString { it.content }
        assertFalse("english is an English translation" in opi)
        val partner = PartnerReply().messages(TopicTurn.Input("es", "usted", "Trabajo", "work", IlrLevel.L1, listOf(Turn(Speaker.LEARNER, "Hola.")))).first().content
        assertFalse("reply_english" in partner, partner)
    }

    @Test
    fun aGlossNeverHoldsUpTheNextQuestion() = runTest {
        val glossStarted = CompletableDeferred<Unit>()
        val model = object : LanguageModel {
            override val id = "m"
            override val isLocal = true
            override suspend fun complete(request: CompletionRequest): CompletionResult {
                if (request.messages.first().content.startsWith("Translate")) {
                    glossStarted.complete(Unit)
                    awaitCancellation()
                }
                return CompletionResult("""{"utterance":"¿Y dónde vive usted?"}""", id, null)
            }
        }
        val gateway = AiGateway({ model })
        assertTrue(GlossLine().background)
        val gloss = backgroundScope.async { gateway.run(GlossLine(), GlossLine.Input("es", "¿Cómo se llama usted?")) }
        glossStarted.await()
        val next = gateway.run(OpiInterviewerTurn(), OpiInterviewerTurn.Input("es", "usted", OpiPhase.WARMUP, IlrLevel.L1))
        assertIs<AiResult.Ok<*>>(next)
        assertIs<AiResult.Unavailable>(gloss.await(), "the gloss was deferred, not the question")
    }

    @Test
    fun glossesFillInLaterAndNeverOverwrite() = runTest {
        val model = FakeModel("""{"utterance":"¿Dónde trabaja usted?"}""")
        val s = OpiSession("es", OpiProfile("es", "usted"), emptyList(), emptyList(), AiGateway({ model }), { it.split(' ').size })
        val line = assertNotNull(s.next())
        assertEquals("", line.english, "the live reply has no gloss")
        s.gloss(0, "Where do you work?")
        assertEquals("Where do you work?", s.turns[0].english)
        s.gloss(0, "Something else")
        assertEquals("Where do you work?", s.turns[0].english)
        assertEquals("Where do you work?", s.checkpoint().turns[0].english, "the gloss is checkpointed with the session")

        val t = TopicSession("es", OpiProfile("es", "usted"), Topic("t", "work", "Trabajo", "¿Dónde trabaja?"), AiGateway({ FakeModel("""{"reply":"¡Qué interesante! ¿Le gusta?"}""") }),
            mode = CorrectionsMode.OFF)
        val ex = assertNotNull(t.say("En un hospital."))
        assertEquals("", ex.replyEnglish)
        assertEquals("How interesting! Do you like it?", t.gloss(0, "How interesting! Do you like it?")!!.replyEnglish)
        assertEquals("How interesting! Do you like it?", t.checkpoint().exchanges[0].replyEnglish)
    }

    @Test
    fun aGlossMustBeEnglish() {
        val problems = GlossLine().validate(GlossLine.Input("ja", "お名前は？"), GlossLine.Output("お名前は何ですか"), ValidationContext())
        assertTrue(problems.isNotEmpty())
        assertTrue(GlossLine().validate(GlossLine.Input("ja", "お名前は？"), GlossLine.Output("What is your name?"), ValidationContext()).isEmpty())
    }

    @Test
    fun backgroundWorkWaitsForTheEndWhereItWouldEvictTheConversationsPromptCache() {
        assertTrue(CorrectionsMode.LIVE.backgroundBetweenTurns(gpuActive = true))
        assertTrue(CorrectionsMode.OFF.backgroundBetweenTurns(gpuActive = true), "glosses still come live on a GPU")
        assertFalse(CorrectionsMode.AFTER_ACTION.backgroundBetweenTurns(gpuActive = true))
        CorrectionsMode.entries.forEach { assertFalse(it.backgroundBetweenTurns(gpuActive = false), "$it on a CPU") }
    }
}
