package app.mokuhyo.opi

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.CompletionRequest
import app.mokuhyo.ai.CompletionResult
import app.mokuhyo.ai.LanguageModel
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.lang.LanguageRegistry
import app.mokuhyo.lang.Languages
import app.mokuhyo.testing.repoFile
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BRIEF §11.1 gate_speaking: for every language, an interview completes all five phases against the scripted fallback
 * (the shipped bank, no model) and against a mock model; test mode runs the full plan. Packs from
 * content/packs/<lang>/opi.json (skipped when absent unless MOKUHYO_REQUIRE_PACKS=1).
 */
class RealOpiPacksTest {
    private val requirePacks = System.getenv("MOKUHYO_REQUIRE_PACKS") == "1"
    private val packsDir = runCatching { repoFile("content/packs") }.getOrNull()
    private val registry = LanguageRegistry(packsDir)

    private fun pack(code: String): OpiPack? {
        val f = packsDir?.let { File(it, "$code/opi.json") }?.takeIf { it.isFile }
        if (f == null) {
            if (requirePacks) throw AssertionError("content/packs/$code/opi.json missing")
            println("SKIP $code opi pack not built")
            return null
        }
        return OpiPack.parse(f.readText())
    }

    private fun words(code: String): (String) -> Int = { s -> registry.module(code).segment(s).count { it.isWord } }

    /** A mock interviewer that walks the phases and answers in the language (uses the bank's own lines). */
    private class MockModel(private val pack: OpiPack) : LanguageModel {
        override val id = "mock"
        override val isLocal = true
        private var n = 0
        override suspend fun complete(request: CompletionRequest): CompletionResult {
            val q = pack.questions[n++ % pack.questions.size]
            // Echo the session's current phase (never go back); the session's plan moves it forward.
            val phase = Regex("Now: phase (\\w+)").find(request.messages.last().content)?.groupValues?.get(1) ?: "warmup"
            val text = """{"utterance":${kotlinx.serialization.json.JsonPrimitive(q.prompt)},"english":"x","next_phase":"$phase","topic":"t","domain":"personal"}"""
            return CompletionResult(text, "mock engine", null)
        }
    }

    @Test
    fun scriptedAndMockInterviewsCompleteAllPhasesInEveryLanguage() = runTest {
        Languages.all.forEach { l ->
            val pack = pack(l.code) ?: return@forEach
            assertTrue(pack.questions.size >= 60, "${l.code}: only ${pack.questions.size} bank questions")
            assertTrue(pack.rolePlays.size >= 12, "${l.code}: only ${pack.rolePlays.size} role-plays")
            for (test in listOf(false, true)) {
                val scripted = OpiSession(l.code, pack.profile, pack.questions, pack.rolePlays, AiGateway({ null }), words(l.code), IlrLevel.L1, test = test, random = Random(1))
                val phases = mutableListOf<OpiPhase>()
                val answer = pack.questions.first { it.level == "2" }.prompt // any text in the language works as an answer
                while (true) {
                    val line = scripted.next() ?: break
                    phases += line.phase
                    scripted.answer(answer)
                }
                assertEquals(OpiPhase.entries, phases.distinct(), "${l.code} scripted (test=$test)")
                val plan = if (test) OpiSession.TEST_PLAN else OpiSession.PRACTICE_PLAN
                assertEquals(plan.values.sum(), phases.size, "${l.code} scripted (test=$test) length")
            }
            val mock = MockModel(pack)
            val mocked = OpiSession(l.code, pack.profile, pack.questions, pack.rolePlays, AiGateway({ mock }), words(l.code))
            val phases = mutableListOf<OpiPhase>()
            while (true) {
                val line = mocked.next() ?: break
                assertEquals("mock engine", line.engine, "${l.code}: mock turn fell back")
                phases += line.phase
                mocked.answer(pack.topics.first().opener) // target-language text that isn't one of the questions
            }
            assertEquals(OpiPhase.entries, phases.distinct(), "${l.code} mock")
            assertTrue(pack.topics.size >= 80, "${l.code}: only ${pack.topics.size} topics")
            assertEquals(TopicDomain.entries.map { it.id }.toSet(), pack.topics.map { it.domain }.toSet(), "${l.code}: topic domains")
        }
    }
}
