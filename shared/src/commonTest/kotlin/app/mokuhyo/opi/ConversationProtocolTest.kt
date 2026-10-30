package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.FakeModel
import app.mokuhyo.exam.IlrLevel
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QC 2026-10-30 V8: scripted interactions for the discussion partner and the OPI interviewer. They check prompts and
 * session state, not what a small model would actually say.
 */
class ConversationProtocolTest {
    private val profile = OpiProfile("es", "Address the candidate as usted.")
    private val words: (String) -> Int = { it.split(' ').count { w -> w.isNotBlank() } }
    private val bank = OpiPhase.entries.filter { it != OpiPhase.ROLEPLAY }.flatMap { phase ->
        (1..6).map { n -> BankQuestion("${phase.wireName}-$n", phase.wireName, "1", "¿Pregunta ${phase.wireName} número $n?", "Q$n", "work") }
    }

    private fun utterance(text: String) = """{"utterance":"$text"}"""

    private fun session(model: FakeModel, test: Boolean = false) =
        OpiSession("es", profile, bank, emptyList(), AiGateway({ model }), words, IlrLevel.L1, test = test, random = Random(3))

    private fun FakeModel.lastPrompt() = requests.last().messages.joinToString("\n") { it.content }

    @Test
    fun thePartnerAnswersAQuestionFirstAndIsNotATutor() {
        val input = TopicTurn.Input("es", "usted", "Trabajo", "work", IlrLevel.L1,
            listOf(Turn(Speaker.PARTNER, "¿Dónde trabaja?"), Turn(Speaker.LEARNER, "En una oficina. ¿Y usted?")))
        val text = PartnerReply().messages(input).joinToString("\n") { it.content }
        assertTrue("If they asked a question, answer it first." in text, text)
        assertTrue("A reply may end without a question." in text)
        assertFalse("tutor" in text, "the live partner is not introduced as a tutor")
        assertFalse("Then give feedback" in text || "keep the conversation going with a question" in text, text)
        // The feedback task keeps its feedback section, composed from the same partner fragments.
        val full = TopicTurn().messages(input).joinToString("\n") { it.content }
        assertTrue("If they asked a question, answer it first." in full && "Then give feedback" in full)
    }

    @Test
    fun aClarificationRequestMayRepeatTheQuestionAndIsNotAProbe() = runTest {
        val model = FakeModel(utterance("¿Dónde trabaja usted?"), utterance("¿Dónde trabaja usted?"), utterance("¿Le gusta su trabajo?"), utterance("¿Qué hace los fines de semana?"))
        val s = session(model)
        val phases = ArrayList<OpiPhase>()
        phases += s.next()!!.phase
        s.answer("¿Perdón? ¿Puede repetir?")
        val repeat = s.next()!!
        assertEquals("fake engine", repeat.engine, "the repeat was accepted, not rejected as a duplicate")
        assertTrue("repeat or rephrase your previous question" in model.lastPrompt())
        phases += repeat.phase
        s.answer("Trabajo en un hospital del centro de la ciudad desde hace cinco años.")
        phases += s.next()!!.phase
        s.answer("Sí, me gusta mucho porque ayudo a la gente todos los días.")
        phases += s.next()!!.phase
        // Practice plan: two warm-up questions. The repeat did not use one of them.
        assertEquals(listOf(OpiPhase.WARMUP, OpiPhase.WARMUP, OpiPhase.WARMUP, OpiPhase.LEVEL_CHECK), phases)
        assertEquals(OpiTurnOutcome.NOT_RATED, s.turns.first().outcome)
    }

    @Test
    fun hesitantAnswersGetAFollowUpOnTheSameTopicThenTheTopicChanges() = runTest {
        val model = FakeModel(*Array(12) { utterance("¿Pregunta del modelo número $it, por favor?") })
        val s = session(model)
        val prompts = ArrayList<String>()
        repeat(6) {
            val line = s.next() ?: return@repeat
            prompts += model.lastPrompt()
            s.answer(if (line.phase == OpiPhase.WARMUP) "Me llamo Ana y vivo en Madrid con mi familia." else "Eh... sí, bueno, no sé.")
        }
        val levelCheck = prompts.drop(2) // two warm-up questions first (practice plan)
        assertTrue("Topic area for this question:" in levelCheck[0], levelCheck[0])
        assertTrue("Stay on the current topic" in levelCheck[1], "a hesitant answer gets a related follow-up: ${levelCheck[1]}")
        assertTrue("Topic area for this question:" in levelCheck[2], "after the follow-up the topic changes: ${levelCheck[2]}")
        assertFalse(prompts.any { "Topic area for this question: abstract" in it || "Topic area for this question: hypothetical" in it },
            "no abstract topics for a learner around ILR 1")
    }

    @Test
    fun theInterviewerNeverCoachesInTestMode() = runTest {
        val model = FakeModel(utterance("¿Cómo se llama usted?"))
        session(model, test = true).next()
        val text = model.lastPrompt()
        assertTrue("Do not coach or correct the candidate during the interview." in text, text)
    }

    @Test
    fun warmUpTopicsSuitTheWarmUp() = runTest {
        val model = FakeModel(utterance("¿Cómo se llama usted?"))
        session(model).next()
        val domain = Regex("Topic area for this question: (\\w+)").find(model.lastPrompt())!!.groupValues[1]
        assertTrue(domain in OpiSession.domainsFor(OpiPhase.WARMUP, IlrLevel.L1), domain)
    }
}
