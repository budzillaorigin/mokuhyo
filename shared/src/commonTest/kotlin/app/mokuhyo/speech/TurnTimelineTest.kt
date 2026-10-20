package app.mokuhyo.speech

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.LocalLlamaModel
import app.mokuhyo.ai.LocalLlmBridge
import app.mokuhyo.ai.LocalSttBridge
import app.mokuhyo.ai.ModelFile
import app.mokuhyo.ai.ModelInfo
import app.mokuhyo.ai.ModelKind
import app.mokuhyo.ai.WhisperRecognizer
import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.opi.OpiInterviewerTurn
import app.mokuhyo.opi.OpiPhase
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import app.mokuhyo.opi.TurnFeedback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TurnTimelineTest {
    /** A clock that moves 1 ms every time it is read, so every mark is strictly later than the previous one. */
    private class TickClock {
        var nanos = 0L
        val read: () -> Long = { nanos += 1_000_000; nanos }
    }

    private val info = ModelInfo(
        id = "tiny", name = "Tiny", kind = ModelKind.LLM, minRamGb = 1, contextSize = 2048, license = "Apache-2.0",
        files = listOf(ModelFile("t.gguf", "https://example.invalid/t.gguf", "00", 1)),
    )

    /** Streams [replies] token by token (one reply per call), like the JNI bridge. */
    private class StreamingBridge(vararg replies: String) : LocalLlmBridge {
        private val queue = ArrayDeque(replies.toList())
        var hold: CompletableDeferred<Unit>? = null
        private var pending: ((String?, String?) -> Unit)? = null
        override fun isLoaded() = true
        override fun load(modelPath: String, contextSize: Int, onDone: (String?) -> Unit) = onDone(null)
        override fun generate(messages: List<ChatMessage>, grammar: String?, maxTokens: Int, temperature: Double, stop: List<String>,
                              onToken: (String) -> Unit, onDone: (String?, String?) -> Unit) {
            if (hold != null) { pending = onDone; hold!!.complete(Unit); return }
            val reply = queue.removeFirst()
            reply.chunked(4).forEach(onToken)
            onDone(reply, null)
        }
        override fun cancel() { pending?.invoke(null, LocalLlmBridge.CANCELLED); pending = null }
        override fun unload() {}
    }

    private class Stt(val text: String) : LocalSttBridge {
        override fun isLoaded() = true
        override fun load(modelPath: String, onDone: (String?) -> Unit) = onDone(null)
        override fun transcribe(samples: FloatArray, language: String, onDone: (String?, String?) -> Unit) =
            onDone("""[{"t0":0,"t1":900,"text":"$text"}]""", null)
    }

    private val question = OpiInterviewerTurn.Input("es", "usted", OpiPhase.WARMUP, IlrLevel.L1,
        listOf(Turn(Speaker.PARTNER, "¿Cómo se llama usted?"), Turn(Speaker.LEARNER, "Me llamo Ana Torres.")))

    /** One learner answer → partner line through the real gateway, recognizer and model wrappers, with fake engines. */
    private suspend fun fakeTurn(timeline: TurnTimeline, gateway: AiGateway, stt: WhisperRecognizer) {
        val turn = timeline.beginTurn()
        withContext(TimelineTrace(timeline, turn)) {
            timeline.mark(TurnStage.LAST_SPEECH)
            timeline.mark(TurnStage.VAD_ENDPOINT)
            timeline.mark(TurnStage.CAPTURE_STOP)
            timeline.note { answerMs = 4_000 }
            traceMark(TurnStage.ASR_QUEUED)
            val heard = stt.transcribe(ShortArray(16_000), "es", "")
            assertEquals("Me llamo Ana Torres.", heard.text)
            traceMark(TurnStage.CHECKPOINT_SAVED)
            gateway.run(OpiInterviewerTurn(), question)
            traceMark(TurnStage.TTS_START)
            traceMark(TurnStage.TTS_FIRST_PCM)
            timeline.note { ttsSynthMs = 300; ttsAudioMs = 1_500 }
            traceMark(TurnStage.OUTPUT_START)
            traceMark(TurnStage.OUTPUT_END)
        }
    }

    private val reply = """{"utterance":"Mucho gusto, señora Torres. ¿De dónde es usted?","english":"Nice to meet you. Where are you from?"}"""

    @Test
    fun ordersEveryStageOfAFakePipeline() = runTest {
        val clock = TickClock()
        val timeline = TurnTimeline("s1", "OPI", "es", clock.read)
        val gateway = AiGateway({ LocalLlamaModel(StreamingBridge(reply), info) })
        fakeTurn(timeline, gateway, WhisperRecognizer(Stt("Me llamo Ana Torres.")))
        val order = timeline.events().map { it.stage }
        assertEquals(
            listOf(TurnStage.LAST_SPEECH, TurnStage.VAD_ENDPOINT, TurnStage.CAPTURE_STOP, TurnStage.ASR_QUEUED, TurnStage.ASR_START, TurnStage.ASR_END,
                TurnStage.CHECKPOINT_SAVED, TurnStage.LLM_QUEUED, TurnStage.LLM_START, TurnStage.LLM_FIRST_TOKEN, TurnStage.LLM_VALIDATED,
                TurnStage.TTS_START, TurnStage.TTS_FIRST_PCM, TurnStage.OUTPUT_START, TurnStage.OUTPUT_END),
            order,
        )
        val t = timeline.snapshot().turns.single()
        assertEquals(1, t.llmAttempts)
        val stages = t.stages
        // Every stage the headline covers is inside it.
        val headline = assertNotNull(stages[TurnTimeline.FIRST_AUDIO])
        listOf("endpoint", "asr", "llm_queue", "llm_first_token", "llm_validated", "tts_first_pcm").forEach {
            assertTrue(assertNotNull(stages[it], it) < headline, "$it within the headline")
        }
        assertTrue(t.events.zipWithNext().all { (a, b) -> a.ms <= b.ms }, "exported marks are in time order")
    }

    @Test
    fun cancellationAndRetryProduceTheRightEntries() = runTest {
        val clock = TickClock()
        val timeline = TurnTimeline("s2", "OPI", "es", clock.read)
        val bridge = StreamingBridge(reply)
        val gateway = AiGateway({ LocalLlamaModel(bridge, info) })
        val turn = timeline.beginTurn()
        // An ASR attempt that is cancelled, then retried: the retry's own start/end are what the report uses.
        timeline.mark(TurnStage.ASR_QUEUED)
        timeline.mark(TurnStage.ASR_START)
        timeline.mark(TurnStage.CANCEL_REQUESTED)
        timeline.mark(TurnStage.CANCEL_EFFECTIVE)
        timeline.mark(TurnStage.ASR_QUEUED)
        timeline.mark(TurnStage.ASR_START)
        timeline.mark(TurnStage.ASR_END)
        // A generation cancelled mid-flight: queued and started, never validated.
        bridge.hold = CompletableDeferred()
        val job = launch(TimelineTrace(timeline, turn)) { gateway.run(OpiInterviewerTurn(), question) }
        bridge.hold!!.await()
        timeline.mark(TurnStage.CANCEL_REQUESTED)
        job.cancel()
        job.join()
        timeline.mark(TurnStage.CANCEL_EFFECTIVE)
        // Then retried: an invalid answer (it repeats the earlier question) and a valid one in the same run.
        bridge.hold = null
        val retry = StreamingBridge("""{"utterance":"¿Cómo se llama usted?","english":"What is your name?"}""", reply)
        withContext(TimelineTrace(timeline, turn)) { AiGateway({ LocalLlamaModel(retry, info) }).run(OpiInterviewerTurn(), question) }

        val stages = timeline.events().map { it.stage }
        assertEquals(2, stages.count { it == TurnStage.CANCEL_REQUESTED })
        assertEquals(2, stages.count { it == TurnStage.LLM_QUEUED }, "the cancelled run and the retry")
        assertEquals(3, stages.count { it == TurnStage.LLM_START }, "one start per attempt")
        assertEquals(1, stages.count { it == TurnStage.LLM_VALIDATED })
        val t = timeline.snapshot().turns.single()
        assertEquals(3, t.llmAttempts)
        assertEquals(1, t.stages["asr"], "the retried ASR start → end, not the cancelled one")
        assertNotNull(t.stages["cancel"])
    }

    @Test
    fun backgroundFeedbackNeverMarksTheTurn() = runTest {
        val timeline = TurnTimeline("s3", "OPI", "es", TickClock().read)
        timeline.beginTurn()
        val feedback = """{"corrected":"Me llamo Ana.","rewrite":"Me llamo Ana.","turn_level":"1"}"""
        val gateway = AiGateway({ LocalLlamaModel(StreamingBridge(feedback), info) })
        // Launched without the trace, like the FeedbackQueue worker.
        val bg = async { gateway.run(TurnFeedback(), TurnFeedback.Input("es", "usted", "¿Su nombre?", "Me llamo Ana.")) }
        bg.await()
        assertTrue(timeline.events().isEmpty())
    }

    @Test
    fun warmupIsReportedApartFromTurns() {
        val timeline = TurnTimeline("s4", "OPI", "es", TickClock().read)
        timeline.warmup("llm", 1_234)
        timeline.beginTurn(cold = true)
        val s = timeline.snapshot()
        assertEquals(mapOf("llm" to 1_234L), s.warmupMs)
        assertTrue(s.turns.single().stages.isEmpty())
        val summary = TimingReport.summarize(listOf(s))
        assertEquals(1_234L, summary.warmupMs["llm"])
        assertFalse(summary.warm.containsKey(TurnTimeline.FIRST_AUDIO))
    }

    private fun report(sessions: List<TimingReport.Session>) = TimingReport(
        generatedBy = "test", appVersion = "0.1.0-dev", machine = TimingReport.Machine("linux", "x86_64", 8, 16.0), real = false,
        sessions = sessions, summary = TimingReport.summarize(sessions, listOf(TimingReport.CancelSample("llm", 40))),
        notRun = mapOf("tts" to "no voice for fa"), cancellation = listOf(TimingReport.CancelSample("llm", 40)),
    )

    @Test
    fun benchReportSchemaRoundTrips() = runTest {
        val timeline = TurnTimeline("s5", "BENCH", "es", TickClock().read)
        timeline.setup = TimelineSetup("tiny", "ab12", "es_ES-davefx-medium", "cpu", 4, 2, 1)
        fakeTurn(timeline, AiGateway({ LocalLlamaModel(StreamingBridge(reply), info) }), WhisperRecognizer(Stt("Me llamo Ana Torres.")))
        val r = report(listOf(timeline.snapshot()))
        val parsed = TimingReport.parse(r.toJson())
        assertEquals(r, parsed)
        assertEquals(TimingReport.FORMAT, parsed.format)
        assertEquals(0.2, parsed.summary.ttsRealTimeFactor)
        assertTrue("| first_audio |" in r.toMarkdown() || "first_audio" in r.toMarkdown())
        assertTrue("dry run" in r.toMarkdown(), "a mock run never reads as a latency measurement")
    }

    @Test
    fun exportContainsNoTranscriptText() = runTest {
        val timeline = TurnTimeline("s6", "OPI", "es", TickClock().read)
        fakeTurn(timeline, AiGateway({ LocalLlamaModel(StreamingBridge(reply), info) }), WhisperRecognizer(Stt("Me llamo Ana Torres.")))
        // A fallback reason can quote model output; only its category is stored.
        timeline.note { fallback = FallbackKind.ofReason("the model's answer failed checks (utterance repeats an earlier question (\"¿Cómo se llama usted?\"))") }
        val r = report(listOf(timeline.snapshot()))
        val exported = r.toJson() + r.toMarkdown()
        listOf("Ana", "Torres", "Mucho gusto", "dónde", "Where are you from", "Cómo se llama").forEach {
            assertFalse(it in exported, "export must not contain \"$it\"")
        }
        assertEquals(FallbackKind.INVALID.wire, r.sessions.single().turns.single().fallback)
    }

    @Test
    fun percentilesAreNearestRank() {
        val v = (1..20).map { it * 100L }
        assertEquals(1_000, TimingReport.percentile(v, 50.0))
        assertEquals(1_900, TimingReport.percentile(v, 95.0))
        assertEquals(0, TimingReport.percentile(emptyList(), 50.0))
    }
}
