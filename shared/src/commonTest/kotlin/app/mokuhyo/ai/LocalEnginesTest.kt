package app.mokuhyo.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val testModel = ModelInfo(
    id = "phi-test", name = "Phi test", kind = ModelKind.LLM, minRamGb = 4, contextSize = 2048, license = "Apache-2.0",
    files = listOf(ModelFile("q.gguf", "https://example.invalid/q.gguf", "00", 1)),
)

private class FakeLlmBridge(
    var reply: String? = "{\"ok\":true}<|im_end|>",
    var loadError: String? = null,
    var hold: Boolean = false,
    var error: String = "boom",
) : LocalLlmBridge {
    var loaded = false
    var loads = 0
    var unloads = 0
    var loadedWith: Pair<String, Int>? = null
    var prompt: String? = null
    var grammar: String? = null
    var stop: List<String> = emptyList()
    var cancelled = false
    private var pending: ((String?, String?) -> Unit)? = null

    override fun isLoaded() = loaded
    override fun load(modelPath: String, contextSize: Int, onDone: (String?) -> Unit) {
        loadedWith = modelPath to contextSize
        loads++
        loaded = loadError == null
        onDone(loadError)
    }

    override fun generate(
        prompt: String, grammar: String?, maxTokens: Int, temperature: Double, stop: List<String>,
        onToken: (String) -> Unit, onDone: (String?, String?) -> Unit,
    ) {
        this.prompt = prompt
        this.grammar = grammar
        this.stop = stop
        if (hold) pending = onDone else onDone(reply, if (reply == null) error else null)
    }

    override fun cancel() {
        cancelled = true
        pending?.invoke(null, "cancelled")
    }

    override fun unload() {
        unloads++
        loaded = false
    }
}

class LocalEnginesTest {
    @Test
    fun chatMlFormat() {
        val prompt = LocalLlamaModel.chatMl(listOf(ChatMessage(Role.SYSTEM, "sys"), ChatMessage(Role.USER, "こんにちは")))
        assertEquals(
            "<|im_start|>system\nsys<|im_end|>\n<|im_start|>user\nこんにちは<|im_end|>\n<|im_start|>assistant\n",
            prompt,
        )
    }

    @Test
    fun loadsThenGeneratesWithGrammar() = runTest {
        val bridge = FakeLlmBridge()
        val model = LocalLlamaModel(bridge, testModel, modelPath = "/models/q.gguf")
        val schema = JsonSchema.Obj(listOf("ok" to JsonSchema.Bool))
        val result = model.complete(CompletionRequest(listOf(ChatMessage(Role.USER, "hi")), jsonSchema = schema, stop = listOf("\n\n")))
        assertEquals("/models/q.gguf" to 2048, bridge.loadedWith)
        assertEquals("{\"ok\":true}", result.text)
        assertEquals("on-device Phi test", result.engine)
        assertEquals(schema.toGbnf(), bridge.grammar)
        assertEquals(listOf("\n\n", "<|im_end|>"), bridge.stop)
        assertTrue(bridge.prompt!!.endsWith("<|im_start|>assistant\n"))
        assertTrue(model.isLocal)
    }

    @Test
    fun noGrammarWithoutSchema() = runTest {
        val bridge = FakeLlmBridge(reply = "はい").apply { loaded = true }
        LocalLlamaModel(bridge, testModel).complete(CompletionRequest(listOf(ChatMessage(Role.USER, "hi"))))
        assertNull(bridge.grammar)
    }

    @Test
    fun failuresBecomeAiExceptions() = runTest {
        assertFailsWith<AiException> { LocalLlamaModel(FakeLlmBridge(), testModel, modelPath = null).complete(CompletionRequest(emptyList())) }
        assertFailsWith<AiException> {
            LocalLlamaModel(FakeLlmBridge(loadError = "out of memory"), testModel, "/m").complete(CompletionRequest(emptyList()))
        }
        assertFailsWith<AiException> {
            LocalLlamaModel(FakeLlmBridge(reply = null).apply { loaded = true }, testModel).complete(CompletionRequest(emptyList()))
        }
    }

    @Test
    fun cancellationStopsNativeGeneration() = runTest {
        val bridge = FakeLlmBridge(hold = true).apply { loaded = true }
        val job = async { LocalLlamaModel(bridge, testModel).complete(CompletionRequest(emptyList())) }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(bridge.cancelled)
        assertTrue(job.isCancelled)
    }

    @Test
    fun whisperBridgeTranscribes() = runTest {
        var received: FloatArray? = null
        val bridge = object : LocalSttBridge {
            var loaded = false
            override fun isLoaded() = loaded
            override fun load(modelPath: String, onDone: (String?) -> Unit) { loaded = true; onDone(null) }
            override fun transcribe(samples: FloatArray, language: String, onDone: (String?, String?) -> Unit) {
                received = samples
                onDone("""[{"t0":0,"t1":1200,"text":" こんにちは。"},{"t0":1200,"t1":2500,"text":"元気です。"}]""", null)
            }
        }
        val t = WhisperRecognizer(bridge, "/m/ggml-base.bin", "Whisper base").transcribe(shortArrayOf(0, 16384, -32768))
        assertEquals("こんにちは。元気です。", t.text)
        assertEquals(listOf(TranscriptSegment(0, 1200, "こんにちは。"), TranscriptSegment(1200, 2500, "元気です。")), t.segments)
        assertEquals("on-device Whisper base", t.engine)
        assertContentEquals(floatArrayOf(0f, 0.5f, -1f), received)
    }

    @Test
    fun wavHeader() {
        val wav = Wav.encode(shortArrayOf(1, -1), 16_000)
        assertEquals(48, wav.size)
        assertEquals("RIFF", wav.decodeToString(0, 4))
        assertEquals("WAVE", wav.decodeToString(8, 12))
        assertEquals("data", wav.decodeToString(36, 40))
        assertEquals(40, wav[4].toInt()) // RIFF chunk size = 36 + 4 data bytes
        assertEquals(0x80.toByte(), wav[24]) // 16000 = 0x3E80, little-endian
        assertEquals(0x3E.toByte(), wav[25])
        assertEquals(1, wav[44].toInt())
        assertEquals(-1, wav[46].toInt())
        assertEquals(-1, wav[47].toInt())
    }

    // --- F-23: model lifecycle ---

    @Test
    fun switchingModelsReloads() = runTest {
        val bridge = FakeLlmBridge()
        val slot = LoadedModelSlot()
        LocalLlamaModel(bridge, testModel, "/models/a.gguf", slot).complete(CompletionRequest(emptyList()))
        LocalLlamaModel(bridge, testModel, "/models/a.gguf", slot).complete(CompletionRequest(emptyList()))
        assertEquals(1, bridge.loads, "same file: loaded once")
        LocalLlamaModel(bridge, testModel, "/models/b.gguf", slot).complete(CompletionRequest(emptyList()))
        assertEquals(2, bridge.loads)
        assertEquals(1, bridge.unloads)
        assertEquals("/models/b.gguf", bridge.loadedWith?.first)
        assertEquals("/models/b.gguf", slot.path)
    }

    @Test
    fun modelLoadedOutsideTheSlotIsReplaced() = runTest {
        val bridge = FakeLlmBridge().apply { loaded = true } // e.g. left over from before a settings change
        LocalLlamaModel(bridge, testModel, "/models/new.gguf", LoadedModelSlot()).ensureLoaded("/models/new.gguf")
        assertEquals(1, bridge.unloads)
        assertEquals("/models/new.gguf", bridge.loadedWith?.first)
    }

    @Test
    fun slotUnloadForgetsThePath() = runTest {
        val bridge = FakeLlmBridge()
        val slot = LoadedModelSlot()
        LocalLlamaModel(bridge, testModel, "/models/a.gguf", slot).complete(CompletionRequest(emptyList()))
        slot.unload(bridge)
        assertNull(slot.path)
        assertEquals(1, bridge.unloads)
        LocalLlamaModel(bridge, testModel, "/models/a.gguf", slot).complete(CompletionRequest(emptyList()))
        assertEquals(2, bridge.loads)
    }

    // --- F-10: cancellation contract ---

    @Test
    fun cancelledStatusBecomesAiCancelled() = runTest {
        for (status in listOf("cancelled", "cancelled: unloaded", "Cancelled by newer request")) {
            val bridge = FakeLlmBridge(reply = null, error = status).apply { loaded = true }
            assertFailsWith<AiCancelledException>(status) { LocalLlamaModel(bridge, testModel).complete(CompletionRequest(emptyList())) }
        }
        val failed = FakeLlmBridge(reply = null, error = "out of memory").apply { loaded = true }
        val e = assertFailsWith<AiException> { LocalLlamaModel(failed, testModel).complete(CompletionRequest(emptyList())) }
        assertTrue(e !is AiCancelledException)
    }

    // --- F-41 ---

    @Test
    fun whisperCancellationStopsTheBridge() = runTest {
        val bridge = object : LocalSttBridge, CancellableSttBridge {
            var cancelled = false
            var pending: ((String?, String?) -> Unit)? = null
            override fun isLoaded() = true
            override fun load(modelPath: String, onDone: (String?) -> Unit) = onDone(null)
            override fun transcribe(samples: FloatArray, language: String, onDone: (String?, String?) -> Unit) { pending = onDone }
            override fun cancel() {
                cancelled = true
                pending?.invoke(null, "cancelled")
            }
        }
        val job = async { WhisperRecognizer(bridge, "/m.bin").transcribe(ShortArray(16), "ja") }
        runCurrent()
        job.cancel()
        runCurrent()
        assertTrue(bridge.cancelled)
        assertTrue(job.isCancelled)
    }

    @Test
    fun whisperCancelledStatusBecomesAiCancelled() = runTest {
        val bridge = object : LocalSttBridge {
            override fun isLoaded() = true
            override fun load(modelPath: String, onDone: (String?) -> Unit) = onDone(null)
            override fun transcribe(samples: FloatArray, language: String, onDone: (String?, String?) -> Unit) = onDone(null, "cancelled")
        }
        assertFailsWith<AiCancelledException> { WhisperRecognizer(bridge).transcribe(ShortArray(16), "ja") }
    }
}
