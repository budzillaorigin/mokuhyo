package app.mokuhyo.speech

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.CompletionRequest
import app.mokuhyo.ai.CompletionResult
import app.mokuhyo.ai.LanguageModel
import app.mokuhyo.ai.Role
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.opi.OpiInterviewerTurn
import app.mokuhyo.opi.OpiPhase
import app.mokuhyo.speech.Preflight.Check
import app.mokuhyo.speech.Preflight.Kind
import app.mokuhyo.speech.Preflight.Result
import app.mokuhyo.speech.Preflight.State
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreflightTest {
    private fun ok(kind: Kind) = Check(kind, kind.wire) { Result.Ok() }

    @Test
    fun eachFailingCheckBlocksStartWithAnActionableMessage() = runTest {
        for (failing in Kind.entries) {
            val checks = Kind.entries.map { k -> if (k == failing) Check(k, k.wire) { Result.Failed("Fix ${k.wire} in Settings → Speech & audio.") } else ok(k) }
            val r = Preflight(checks).run()
            assertEquals(failing, r.blocking?.kind)
            assertEquals("Fix ${failing.wire} in Settings → Speech & audio.", r.blocking?.message)
            assertTrue(r.items.all { it.state == State.OK || it.state == State.FAILED }, "every check still ran: ${r.items}")
        }
    }

    @Test
    fun somethingNotInstalledDoesNotBlockAndAnErrorDoes() = runTest {
        val r = Preflight(listOf(
            Check(Kind.ASR, "asr") { Result.NotNeeded("not installed: you'll type") },
            Check(Kind.MICROPHONE, "mic") { error("device busy") },
        )).run()
        assertEquals(State.NOT_NEEDED, r.state(Kind.ASR))
        assertEquals(Kind.MICROPHONE, r.blocking?.kind)
        assertTrue(r.blocking!!.message!!.contains("device busy"))
        assertTrue(Preflight(listOf(Check(Kind.ASR, "asr") { Result.NotNeeded("x") })).run().ready)
    }

    @Test
    fun warmUpIsReportedApartFromAnswerTime() = runTest {
        val timeline = TurnTimeline("s", "OPI", "es")
        Preflight(listOf(ok(Kind.LLM), ok(Kind.TTS))).run(timeline)
        val s = timeline.snapshot()
        assertEquals(setOf("llm", "tts"), s.warmupMs.keys)
        assertTrue(s.turns.isEmpty(), "no turn exists before the first question")
    }

    @Test
    fun progressShowsEachStepRunningThenDone() = runTest {
        val seen = ArrayList<List<State>>()
        Preflight(listOf(ok(Kind.OUTPUT), ok(Kind.VOICE))).run { seen += it.items.map { i -> i.state } }
        assertEquals(listOf(
            listOf(State.PENDING, State.PENDING), listOf(State.RUNNING, State.PENDING), listOf(State.OK, State.PENDING),
            listOf(State.OK, State.RUNNING), listOf(State.OK, State.OK),
        ), seen)
    }

    @Test
    fun cancelStopsTheRunningStep() = runTest {
        var reached = false
        val job = launch { Preflight(listOf(Check(Kind.LLM, "llm") { awaitCancellation() }, Check(Kind.TTS, "tts") { reached = true; Result.Ok() })).run() }
        testScheduler.advanceUntilIdle()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled && !reached)
    }

    @Test
    fun warmingLoadsTheModelOnTheInterviewersOwnPrompt() = runTest {
        var request: CompletionRequest? = null
        val model = object : LanguageModel {
            override val id = "m"
            override val isLocal = true
            override suspend fun complete(request: CompletionRequest): CompletionResult { request.also { r -> requestSeen(r) }; return CompletionResult("{", id, null) }
            fun requestSeen(r: CompletionRequest) { request = r }
        }
        val input = OpiInterviewerTurn.Input("es", "Use usted.", OpiPhase.WARMUP, IlrLevel.L1)
        assertNull(AiGateway({ model }).warm(OpiInterviewerTurn(), input))
        assertEquals(1, request!!.maxTokens)
        assertEquals(OpiInterviewerTurn().messages(input).first { it.role == Role.SYSTEM }.content.take(80),
            request!!.messages.first { it.role == Role.SYSTEM }.content.take(80), "the same system prompt the first question uses")
        assertEquals("no model", AiGateway({ null }).warm(OpiInterviewerTurn(), input))
        val broken = object : LanguageModel {
            override val id = "b"
            override val isLocal = true
            override suspend fun complete(request: CompletionRequest): CompletionResult = throw app.mokuhyo.ai.AiException("couldn't load model: out of memory")
        }
        assertEquals("couldn't load model: out of memory", AiGateway({ broken }).warm(OpiInterviewerTurn(), input))
    }
}
