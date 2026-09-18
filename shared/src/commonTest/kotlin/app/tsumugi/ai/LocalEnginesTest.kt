package app.tsumugi.ai

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val qwen = ModelInfo(
    id = "qwen-test", name = "Qwen test", kind = ModelKind.LLM, minRamGb = 4, contextSize = 2048, license = "Apache-2.0",
    files = listOf(ModelFile("q.gguf", "https://example.invalid/q.gguf", "00", 1)),
)

private class FakeLlmBridge(var reply: String? = "{\"ok\":true}<|im_end|>", var loadError: String? = null, var hold: Boolean = false) : LocalLlmBridge {
    var loaded = false
    var loadedWith: Pair<String, Int>? = null
    var prompt: String? = null
    var grammar: String? = null
    var stop: List<String> = emptyList()
    var cancelled = false
    private var pending: ((String?, String?) -> Unit)? = null

    override fun isLoaded() = loaded
    override fun load(modelPath: String, contextSize: Int, onDone: (String?) -> Unit) {
        loadedWith = modelPath to contextSize
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
        if (hold) pending = onDone else onDone(reply, if (reply == null) "boom" else null)
    }

    override fun cancel() {
        cancelled = true
        pending?.invoke(null, "cancelled")
    }

    override fun unload() { loaded = false }
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
        val model = LocalLlamaModel(bridge, qwen, modelPath = "/models/q.gguf")
        val schema = JsonSchema.Obj(listOf("ok" to JsonSchema.Bool))
        val result = model.complete(CompletionRequest(listOf(ChatMessage(Role.USER, "hi")), jsonSchema = schema, stop = listOf("\n\n")))
        assertEquals("/models/q.gguf" to 2048, bridge.loadedWith)
        assertEquals("{\"ok\":true}", result.text)
        assertEquals("on-device Qwen test", result.engine)
        assertEquals(schema.toGbnf(), bridge.grammar)
        assertEquals(listOf("\n\n", "<|im_end|>"), bridge.stop)
        assertTrue(bridge.prompt!!.endsWith("<|im_start|>assistant\n"))
        assertTrue(model.isLocal)
    }

    @Test
    fun noGrammarWithoutSchema() = runTest {
        val bridge = FakeLlmBridge(reply = "はい").apply { loaded = true }
        LocalLlamaModel(bridge, qwen).complete(CompletionRequest(listOf(ChatMessage(Role.USER, "hi"))))
        assertNull(bridge.grammar)
    }

    @Test
    fun failuresBecomeAiExceptions() = runTest {
        assertFailsWith<AiException> { LocalLlamaModel(FakeLlmBridge(), qwen, modelPath = null).complete(CompletionRequest(emptyList())) }
        assertFailsWith<AiException> {
            LocalLlamaModel(FakeLlmBridge(loadError = "out of memory"), qwen, "/m").complete(CompletionRequest(emptyList()))
        }
        assertFailsWith<AiException> {
            LocalLlamaModel(FakeLlmBridge(reply = null).apply { loaded = true }, qwen).complete(CompletionRequest(emptyList()))
        }
    }

    @Test
    fun cancellationStopsNativeGeneration() = runTest {
        val bridge = FakeLlmBridge(hold = true).apply { loaded = true }
        val job = async { LocalLlamaModel(bridge, qwen).complete(CompletionRequest(emptyList())) }
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
}
