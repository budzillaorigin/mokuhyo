package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.FakeModel
import app.mokuhyo.exam.IlrLevel
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CLAUDE.md rule 10: the OPI session against a mock model and against the scripted fallback, in any language. */
class OpiSessionTest {
    private val profile = OpiProfile("es", "Use usted with the candidate.")
    private val words: (String) -> Int = { s -> s.split(Regex("\\s+")).count { it.isNotBlank() } }

    private val bank: List<BankQuestion> = OpiPhase.entries.flatMap { phase ->
        IlrLevel.lowerRange.flatMap { lv ->
            (1..4).map { n -> BankQuestion("${phase.wireName}-${lv.label}-$n", phase.wireName, lv.label, "¿Pregunta ${phase.wireName} ${lv.label} $n?", "Question $n", domain = listOf("work", "family", "travel", "abstract")[n - 1]) }
        }
    }
    private val rolePlays = listOf(RolePlay("rp1", "1", "You are at a hotel.", "receptionist", "Buenas tardes, ¿en qué puedo ayudarle?", "Good afternoon, how can I help you?"))

    private fun scripted(test: Boolean = false) = OpiSession("es", profile, bank, rolePlays, AiGateway({ null }), words, test = test, random = Random(1))

    private val distinctQuestions = listOf(
        "¿Dónde vive usted?", "¿Qué hace los domingos?", "Hábleme de su trabajo.", "¿Cómo era su escuela primaria?",
        "Compare dos ciudades que conoce.", "¿Qué haría con un millón de euros?", "Buenas tardes, ¿tiene reserva?",
        "Lo siento, la habitación no está lista.", "¿Prefiere una cama doble?", "Explique su opinión sobre el servicio militar.",
        "¿Cuál es su comida favorita?", "Describa el último viaje que hizo.", "¿Qué opina del teletrabajo?",
        "Gracias por su paciencia, ¿algo más?", "¿Le gusta el fútbol?", "¿Qué planes tiene para mañana?",
    )

    /** BRIEF_PHASE8 N-00b: every role-play turn carries the same situation; only the first sets it up. */
    @Test
    fun rolePlayTurnsContinueTheSameSituation() = runTest {
        val prompts = mutableListOf<String>()
        var n = 0
        val model = object : app.mokuhyo.ai.LanguageModel {
            override val id = "fake"
            override val isLocal = true
            override suspend fun complete(request: app.mokuhyo.ai.CompletionRequest): app.mokuhyo.ai.CompletionResult {
                prompts += request.messages.last().content
                n++
                val q = distinctQuestions[(n - 1) % distinctQuestions.size]
                return app.mokuhyo.ai.CompletionResult("""{"utterance":"$q","english":"Question $n?"}""", "fake engine", null)
            }
        }
        val s = OpiSession("es", profile, bank, rolePlays, AiGateway({ model }), words, random = Random(1))
        val rolePlayPrompts = mutableListOf<String>()
        while (true) {
            val line = s.next() ?: break
            if (line.phase == OpiPhase.ROLEPLAY) rolePlayPrompts += prompts.last()
            s.answer("Bueno, yo creo que es una pregunta interesante y quiero contestar con cuidado.")
        }
        assertTrue(rolePlayPrompts.size >= 2, "the plan has at least two role-play turns")
        assertTrue(rolePlayPrompts.all { "You are at a hotel." in it && "receptionist" in it }, "every turn knows the situation: ")
        assertTrue("set up the role-play situation" in rolePlayPrompts[0] && "already set up" !in rolePlayPrompts[0])
        assertTrue(rolePlayPrompts.drop(1).all { "already set up" in it && "stay in your role in the same role-play" in it })
    }

    @Test
    fun scriptedFallbackRunsAllFivePhases() = runTest {
        val s = scripted()
        val phases = mutableListOf<OpiPhase>()
        while (true) {
            val line = s.next() ?: break
            phases += line.phase
            assertNull(line.engine)
            s.answer("Sí, trabajo en una oficina en el centro de la ciudad y me gusta mucho mi trabajo porque es interesante.")
        }
        assertEquals(OpiPhase.entries, phases.distinct())
        assertEquals(OpiSession.PRACTICE_PLAN.values.sum(), phases.size)
        assertTrue(s.finished)
        assertTrue(s.turns.any { it.question == rolePlays.single().opening }, "role-play opening used")
        assertEquals(phases.size, s.turns.map { it.question }.distinct().size, "no repeated questions")
    }

    @Test
    fun testModeRunsTheFullPlan() = runTest {
        val s = scripted(test = true)
        var n = 0
        while (s.next() != null) {
            s.answer("Bueno, creo que es una pregunta difícil, pero en mi opinión depende de muchas cosas.")
            n++
        }
        assertEquals(OpiSession.TEST_PLAN.values.sum(), n)
    }

    @Test
    fun levelAdaptsUpOnLongAnswersAndDownOnBreakdown() = runTest {
        val s = scripted()
        while (s.phase != OpiPhase.LEVEL_CHECK) { s.next(); s.answer("Sí.") }
        val before = s.workingLevel
        s.next()
        s.answer((1..40).joinToString(" ") { "palabra$it" })
        assertTrue(s.workingLevel > before, "should move up from $before")
        s.next()
        s.answer("No.")
        assertTrue(s.turns.last().outcome == OpiTurnOutcome.BREAKDOWN)
    }

    @Test
    fun noModelMeansSelfRating() = runTest {
        val s = scripted()
        s.next()
        s.answer("Hola, me llamo Ana.")
        val rating = s.rate()
        assertTrue(rating.needsSelfRating)
        val checklist = mapOf("0+" to listOf("a"), "1" to listOf("b"), "1+" to listOf("c"))
        assertEquals("1", s.selfRate(checklist, setOf("a", "b")).estimate)
    }

    @Test
    fun mockModelDrivesTheInterviewAndRates() = runTest {
        val turn = """{"utterance":"¿Dónde vive usted?","english":"Where do you live?","next_phase":"level_check","topic":"home","domain":"personal"}"""
        val rate = """{"functions":{"level":"2","evidence":"Narrates past events.","quotes":["fui a Madrid"]},
            "context_content":{"level":"2","evidence":"Concrete topics.","quotes":[]},
            "accuracy":{"level":"1+","evidence":"Some agreement errors.","quotes":[]},
            "text_type":{"level":"2","evidence":"Paragraph-length answers.","quotes":[]},
            "sustained_level":"2","breakdown_level":"2+","estimate":"2",
            "rationale":"The candidate narrates and describes in paragraphs on concrete topics but breaks down when asked to support an opinion.",
            "next_steps":["Practise supporting opinions with two reasons.","Review past-tense agreement.","Describe a process step by step."]}"""
        val model = FakeModel(turn, rate)
        val s = OpiSession("es", profile, emptyList(), emptyList(), AiGateway({ model }), words)
        val line = s.next()
        assertNotNull(line)
        assertEquals("fake engine", line.engine)
        // Slot filling (BRIEF_PHASE8 N-00b): the plan moves the phase, not the model's "next_phase"; the session's
        // topic area and question type are in the prompt.
        assertEquals(OpiPhase.WARMUP, s.phase)
        val prompt = model.requests.first().messages.last().content
        assertTrue("Kind of question: an easy personal question" in prompt && "Topic area for this question: " in prompt, prompt)
        assertEquals(0, s.scriptedTurns)
        s.answer("Vivo en Madrid. El año pasado fui a Madrid para trabajar.")
        val rating = s.rate()
        assertEquals("2", rating.estimate)
        assertEquals(3, rating.nextSteps.size)
        assertFalse(rating.needsSelfRating)
        assertEquals(setOf("functions", "context_content", "accuracy", "text_type"), rating.factors.keys)
    }

    @Test
    fun invented_quotesAreRejected() = runTest {
        val rate = """{"functions":{"level":"3","evidence":"x","quotes":["discurso abstracto brillante"]},
            "context_content":{"level":"3","evidence":"x","quotes":[]},"accuracy":{"level":"3","evidence":"x","quotes":[]},
            "text_type":{"level":"3","evidence":"x","quotes":[]},"sustained_level":"3","estimate":"3",
            "rationale":"Excellent extended discourse on abstract topics throughout the interview.","next_steps":["a","b","c"]}"""
        val s = OpiSession("es", profile, emptyList(), emptyList(), AiGateway({ FakeModel(rate, rate) }), words)
        s.answer("Me llamo Ana.")
        assertTrue(s.rate().needsSelfRating)
    }

    @Test
    fun topicConversationTracksRollingLevelAndRecurringErrors() = runTest {
        fun reply(level: String) = """{"reply":"¡Qué interesante! ¿Por qué?","corrected":"Yo fui al parque.",""" +
            """"changes":[{"from":"va","to":"fui","why":"past tense"}],"rewrite":"Fui al parque.","vocabulary":[],"turn_level":"$level"}"""
        val model = FakeModel(reply("1"), reply("1"), reply("2"), reply("2"), reply("2"), reply("2")) // reply + critique per turn
        val topic = Topic("t1", "daily_life", "Weekend plans", "¿Qué hizo el fin de semana?")
        val t = TopicSession("es", profile, topic, AiGateway({ model }))
        repeat(3) { assertNotNull(t.say("Yo va al parque.")) }
        assertEquals(IlrLevel.L2, t.rollingLevel)
        assertEquals(listOf("1", "2", "2"), t.levelTrack)
        assertEquals(1, t.recurringErrors().size)
        assertTrue(t.redoLast())
        assertEquals(2, t.exchanges.size)
    }
}

class InterviewerRepeatTest {
    @Test
    fun repeatedQuestionsAreRejected() {
        val task = OpiInterviewerTurn()
        val input = OpiInterviewerTurn.Input("es", "usted", OpiPhase.LEVEL_CHECK, IlrLevel.L2,
            listOf(Turn(Speaker.PARTNER, "¿Qué te parece hacer un viaje a España?"), Turn(Speaker.LEARNER, "Me gustaría.")))
        val repeat = OpiInterviewerTurn.Output("¿Qué te parece si hacemos un viaje a España?", "", OpiPhase.LEVEL_CHECK)
        val fresh = OpiInterviewerTurn.Output("¿Cómo es un día normal en su trabajo?", "", OpiPhase.LEVEL_CHECK)
        assertTrue(task.validate(input, repeat, app.mokuhyo.ai.ValidationContext()).any { "repeats" in it })
        assertTrue(task.validate(input, fresh, app.mokuhyo.ai.ValidationContext()).isEmpty())
    }

    /** Smoke regression: a usable question with a backward next_phase was thrown away; the session clamps it instead. */
    @Test
    fun backwardNextPhaseIsAccepted() {
        val task = OpiInterviewerTurn()
        val input = OpiInterviewerTurn.Input("es", "usted", OpiPhase.PROBE, IlrLevel.L2, listOf(Turn(Speaker.PARTNER, "¿Dónde vive usted?"), Turn(Speaker.LEARNER, "En Madrid.")))
        val out = OpiInterviewerTurn.Output("¿Cómo ha cambiado su barrio en los últimos diez años?", "", OpiPhase.WARMUP)
        assertTrue(task.validate(input, out, app.mokuhyo.ai.ValidationContext()).isEmpty())
    }
}

class SimilarQuestionsTest {
    @Test
    fun bigramSimilarity() {
        assertTrue(OpiInterviewerTurn.similar("¿Qué te parece hacer un viaje a España?", "¿Qué te parece si hacemos un viaje a España?"))
        assertTrue(!OpiInterviewerTurn.similar("お名前は何ですか。", "お仕事は何ですか。"))
        assertTrue(!OpiInterviewerTurn.similar("¿Dónde vive usted?", "¿Dónde trabaja usted?"))
    }
}
