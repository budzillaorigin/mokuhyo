package app.tsumugi.exam.opi

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.CompletionRequest
import app.tsumugi.ai.CompletionResult
import app.tsumugi.ai.LanguageModel
import app.tsumugi.ai.prompts.OpiPhase
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.practice.OpiBank
import app.tsumugi.practice.OpiQuestion
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import app.tsumugi.practice.OpiPhase as BankPhase

class OpiSessionTest {

    private val banks: Map<IlrLevel, OpiBank> = IlrLevel.lowerRange.associateWith { level ->
        OpiBank(
            level.label,
            BankPhase.entries.flatMap { phase -> (1..4).map { OpiQuestion(phase, "${level.label}-${phase.name}-$it？", "Q $it", "", "llm") } },
            listOf("statement ${level.label} a", "statement ${level.label} b"),
        )
    }

    private val noModel = AiGateway({ null })

    @Test
    fun scriptedInterviewRunsAllPhasesInOrder() = runTest {
        val session = OpiSession(banks, noModel, random = Random(1))
        val phases = mutableListOf<OpiPhase>()
        while (true) {
            val line = session.next() ?: break
            phases += line.phase
            assertTrue(line.english.isNotEmpty())
            assertNull(line.engine)
            session.answer("はい、そうです。")
        }
        assertEquals(OpiSession.PLAN.values.sum(), phases.size)
        assertEquals(phases.sortedBy { it.ordinal }, phases, "phases never go back")
        assertEquals(OpiPhase.entries.toList(), phases.distinct())
        assertTrue(session.finished)
    }

    @Test
    fun strongAnswersRaiseAndBreakdownLowersTheWorkingLevel() = runTest {
        val session = OpiSession(banks, noModel, IlrLevel.L1, random = Random(2))
        while (session.phase != OpiPhase.LEVEL_CHECK) { session.next(); session.answer("はい。") }
        assertEquals(IlrLevel.L1, session.workingLevel)
        session.next()
        session.answer("週末はたいてい家族と一緒に近くの公園へ行って、散歩をしたり、お弁当を食べたりします。")
        assertTrue(session.workingLevel > IlrLevel.L1)
        val raised = session.workingLevel
        session.next()
        session.answer("えっと")
        assertTrue(session.workingLevel < raised)
    }

    @Test
    fun noQuestionsRepeat() = runTest {
        val session = OpiSession(banks, noModel, random = Random(3))
        val asked = mutableListOf<String>()
        while (true) { asked += (session.next() ?: break).japanese; session.answer("はい") }
        assertEquals(asked.size, asked.toSet().size)
    }

    @Test
    fun withoutAModelTheLearnerSelfRates() = runTest {
        val session = OpiSession(banks, noModel)
        session.next()
        session.answer("はい")
        val rating = session.rate()
        assertTrue(rating.needsSelfRating)
        val all = session.checklist().map { it.second }
        val upTo2 = session.checklist().filter { it.first <= IlrLevel.L2 }.map { it.second }.toSet()
        assertEquals(IlrLevel.L2, session.selfRate(upTo2).ilr)
        assertEquals(IlrLevel.L3, session.selfRate(all.toSet()).ilr)
        assertNull(session.selfRate(emptySet()).ilr)
        // A gap at 1+ caps the rating at 1 even if higher statements are checked.
        val gap = all.toSet() - "statement 1+ a"
        assertEquals(IlrLevel.L1, session.selfRate(gap).ilr)
    }

    @Test
    fun modelRatingMapsActflToIlr() = runTest {
        val model = object : LanguageModel {
            override val id = "fake"
            override val isLocal = false
            override suspend fun complete(request: CompletionRequest): CompletionResult {
                val system = request.messages.first().content
                val text = if ("estimate the candidate" in system) {
                    """{"level":"Intermediate High","functions":3,"accuracy":3,"vocabulary":3,"fluency":4,"rationale":"The candidate handled routine questions and narrated in paragraphs with some breakdown.","strengths":["narration"],"next_steps":["past tense"]}"""
                } else {
                    """{"utterance":"お名前は何ですか。","next_phase":"warmup","topic":"name"}"""
                }
                return CompletionResult(text, "fake", null)
            }
        }
        val session = OpiSession(banks, AiGateway({ model }))
        val line = assertNotNull(session.next())
        assertEquals("fake", line.engine)
        session.answer("キムです。")
        val rating = session.rate()
        assertEquals(IlrLevel.L1_PLUS, rating.ilr)
        assertEquals("fake", rating.engine)
        assertEquals(IlrLevel.L2, OpiSession.ilrFor("Advanced Mid"))
    }
}
