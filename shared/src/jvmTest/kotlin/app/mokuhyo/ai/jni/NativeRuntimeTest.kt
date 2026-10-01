package app.mokuhyo.ai.jni

import app.mokuhyo.ai.AiCancelledException
import app.mokuhyo.ai.ChatMessage
import app.mokuhyo.ai.CompletionRequest
import app.mokuhyo.ai.JsonSchema
import app.mokuhyo.ai.LocalLlamaModel
import app.mokuhyo.ai.LocalLlmBridge
import app.mokuhyo.ai.ModelInfo
import app.mokuhyo.ai.ModelKind
import app.mokuhyo.ai.Role
import app.mokuhyo.ai.WhisperRecognizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the real native library (native/build.sh). Every test returns early with a note — never fails — when
 * the library isn't built, so CI without natives stays green. Run against the CPU build with
 * `MOKUHYO_NATIVE_PREFER_CPU=1 ./gradlew :shared:jvmTest --rerun`.
 *
 * The LLM tests use stories260K.gguf (MIT, Andrej Karpathy's tinyllamas, ~1.2 MB), downloaded once into
 * native/build/test-models/ and hash-checked; they skip when offline. Whisper tests run when
 * content/models/ggml-tiny.bin or ggml-base.bin exists.
 */
class NativeRuntimeTest {
    private fun nativeOrSkip(test: String): NativeLibrary.Status? {
        val status = NativeLibrary.load()
        if (!status.isLoaded) {
            println("SKIP $test: native library not built (${status.error}); run native/build.sh")
            return null
        }
        return status
    }

    @Test
    fun loadsAndReportsVariantAndDevices() {
        val status = nativeOrSkip("loadsAndReportsVariantAndDevices") ?: return
        println("native: variant=${status.variant} gpuActive=${status.gpuActive} gpuError=${status.gpuError} path=${status.path}")
        assertEquals(status.variant, GgmlNative.nativeVariant(), "binary variant matches the directory it came from")
        if (NativeLibrary.preferCpu) assertEquals("cpu", status.variant)
        val devices = NativeDevices.list()
        devices.forEach { println("device: $it") }
        assertTrue(devices.any { it.backend == "CPU" }, "the CPU device is always listed")
        if (status.gpuActive) {
            val gpu = devices.first { it.backend != "CPU" }
            assertTrue(gpu.totalBytes > 0, "GPU memory is reported: $gpu")
            assertEquals(if (NativeLibrary.osName() == "macos") "Metal" else "Vulkan", gpu.backend)
        }
        val info = assertNotNull(nativeSystemInfo())
        println("system info: $info")
        assertTrue(info.isNotBlank())
        assertTrue(NativeLibrary.recommendedThreads() >= 2)
    }

    // --- LLM -----------------------------------------------------------------------------------------------------

    private fun loadedLlm(test: String): LlamaJniBridge? {
        nativeOrSkip(test) ?: return null
        val model = tinyModel() ?: run {
            println("SKIP $test: couldn't download stories260K.gguf (offline?)")
            return null
        }
        val bridge = LlamaJniBridge()
        val error = CompletableFuture<String?>()
        bridge.load(model.absolutePath, 1024) { error.complete(it) }
        assertNull(error.get(60, TimeUnit.SECONDS), "model loads")
        assertTrue(bridge.isLoaded())
        println("llm loaded: ${bridge.lastLoadNote}")
        return bridge
    }

    private data class Outcome(val text: String?, val error: String?, val streamed: String)

    private fun LlamaJniBridge.run(
        messages: List<ChatMessage> = PROMPT,
        grammar: String? = null,
        maxTokens: Int = 48,
        temperature: Double = 0.0,
        stop: List<String> = emptyList(),
    ): Outcome {
        val streamed = StringBuffer()
        val done = CompletableFuture<Outcome>()
        generate(messages, grammar, maxTokens, temperature, stop, onToken = { streamed.append(it) }) { text, error ->
            done.complete(Outcome(text, error, streamed.toString()))
        }
        return done.get(120, TimeUnit.SECONDS)
    }

    @Test
    fun generatesStreamsAndReusesTheCache() {
        val bridge = loadedLlm("generatesStreamsAndReusesTheCache") ?: return
        try {
            val first = bridge.run()
            println("greedy: ${first.text}")
            assertNull(first.error)
            val text = assertNotNull(first.text)
            assertTrue(text.isNotBlank(), "some text was generated")
            assertEquals(text, first.streamed, "onToken streamed exactly the result")
            // Same prompt again (KV-cache prefix reuse path), greedy: same output.
            assertEquals(text, bridge.run().text)
            // Sampling with a fresh seed still produces text.
            assertNotNull(bridge.run(temperature = 0.8).text)
        } finally {
            bridge.unload()
        }
    }

    @Test
    fun grammarConstrainsOutputToTheSchema() {
        val bridge = loadedLlm("grammarConstrainsOutputToTheSchema") ?: return
        try {
            val schema = JsonSchema.Obj(listOf("mood" to JsonSchema.Str(enum = listOf("happy", "sad")), "ok" to JsonSchema.Bool))
            // Each `ws` may take up to 20 whitespace characters; leave room so the grammar can always close the object.
            val out = bridge.run(grammar = schema.toGbnf(), maxTokens = 400, temperature = 0.7)
            println("grammar: ${out.text}")
            assertNull(out.error)
            val obj = Json.parseToJsonElement(assertNotNull(out.text)).jsonObject
            assertTrue(obj["mood"]!!.jsonPrimitive.content in setOf("happy", "sad"))
            assertTrue(obj["ok"]!!.jsonPrimitive.content in setOf("true", "false"))
            // A malformed grammar is an error, not a crash.
            val bad = bridge.run(grammar = "root ::= (")
            assertNull(bad.text)
            assertNotNull(bad.error)
            // ...and the bridge keeps working afterwards.
            assertNotNull(bridge.run().text)
        } finally {
            bridge.unload()
        }
    }

    @Test
    fun stopStringsCutTheOutputAndAreExcluded() {
        val bridge = loadedLlm("stopStringsCutTheOutputAndAreExcluded") ?: return
        try {
            val full = assertNotNull(bridge.run(maxTokens = 64).text)
            if (full.length < 12) {
                println("SKIP stop strings: greedy output too short to pick a stop string from: '$full'")
                return
            }
            val stop = full.substring(6, 10)
            val expected = full.substring(0, full.indexOf(stop))
            val cut = bridge.run(maxTokens = 64, stop = listOf(stop))
            println("stop '$stop': '${cut.text}'")
            assertNull(cut.error)
            assertEquals(expected, cut.text)
            assertEquals(expected, cut.streamed, "the stop string never reached onToken")
        } finally {
            bridge.unload()
        }
    }

    @Test
    fun cancelMidGenerationReportsCancelledNotPartialText() {
        val bridge = loadedLlm("cancelMidGenerationReportsCancelledNotPartialText") ?: return
        try {
            val firstToken = CountDownLatch(1)
            val done = CompletableFuture<Pair<String?, String?>>()
            bridge.generate(PROMPT, null, 400, 0.8, emptyList(), onToken = { firstToken.countDown() }) { t, e ->
                done.complete(t to e)
            }
            assertTrue(firstToken.await(60, TimeUnit.SECONDS), "generation started")
            bridge.cancel()
            val (text, error) = done.get(60, TimeUnit.SECONDS)
            assertNull(text, "no partial text on cancel")
            assertTrue(LocalLlmBridge.isCancelled(error), "error is 'cancelled…': $error")
            // The high-water mark only covers calls issued before cancel(): the next one runs normally.
            assertNotNull(bridge.run().text)
        } finally {
            bridge.unload()
        }
    }

    @Test
    fun cancelThroughLocalLlamaModelThrowsAiCancelledException() {
        val bridge = loadedLlm("cancelThroughLocalLlamaModelThrowsAiCancelledException") ?: return
        val model = tinyModelFile()
        // Cancels the native generation as soon as the first token arrives, like a "Stop" button would.
        val cancelling = object : LocalLlmBridge by bridge {
            override fun generate(
                messages: List<ChatMessage>, grammar: String?, maxTokens: Int, temperature: Double, stop: List<String>,
                onToken: (String) -> Unit, onDone: (String?, String?) -> Unit,
            ) = bridge.generate(messages, grammar, maxTokens, temperature, stop, onToken = {
                onToken(it)
                bridge.cancel()
            }, onDone = onDone)
        }
        val info = ModelInfo("stories260K", "stories260K", ModelKind.LLM, minRamGb = 1, contextSize = 1024, license = "MIT", files = emptyList())
        val llm = LocalLlamaModel(cancelling, info, model.absolutePath)
        try {
            runBlocking {
                withTimeout(120_000) {
                    assertFailsWith<AiCancelledException> {
                        llm.complete(CompletionRequest(PROMPT, maxTokens = 400, temperature = 0.8))
                    }
                }
                // And a normal completion through the same model afterwards.
                val ok = withTimeout(120_000) { LocalLlamaModel(bridge, info, model.absolutePath).complete(CompletionRequest(PROMPT, maxTokens = 24)) }
                assertTrue(ok.text.isNotEmpty())
            }
        } finally {
            bridge.unload()
        }
    }

    @Test
    fun loadFailuresAreReportedNotThrown() {
        nativeOrSkip("loadFailuresAreReportedNotThrown") ?: return
        val bridge = LlamaJniBridge()
        val missing = CompletableFuture<String?>()
        bridge.load("/nonexistent/model.gguf", 1024) { missing.complete(it) }
        assertNotNull(missing.get(30, TimeUnit.SECONDS))
        val notGguf = File.createTempFile("not-a-model", ".gguf").apply { writeText("hello"); deleteOnExit() }
        val bad = CompletableFuture<String?>()
        bridge.load(notGguf.absolutePath, 1024) { bad.complete(it) }
        assertNotNull(bad.get(30, TimeUnit.SECONDS))
        assertTrue(!bridge.isLoaded())
        val gen = CompletableFuture<String?>()
        bridge.generate(PROMPT, null, 8, 0.0, emptyList(), {}) { _, e -> gen.complete(e) }
        assertEquals("No model loaded", gen.get(30, TimeUnit.SECONDS))
    }

    // --- Whisper -------------------------------------------------------------------------------------------------

    private fun loadedWhisper(test: String): WhisperJniBridge? {
        nativeOrSkip(test) ?: return null
        val model = whisperModel() ?: run {
            println("SKIP $test: no content/models/ggml-tiny.bin or ggml-base.bin")
            return null
        }
        val bridge = WhisperJniBridge()
        val error = CompletableFuture<String?>()
        bridge.load(model.absolutePath) { error.complete(it) }
        assertNull(error.get(120, TimeUnit.SECONDS), "speech model loads")
        return bridge
    }

    @Test
    fun whisperTranscribesToSegmentJson() {
        val bridge = loadedWhisper("whisperTranscribesToSegmentJson") ?: return
        try {
            val samples = toneThenSilence(seconds = 3)
            for (language in listOf("ja", "es", "zh-Hans")) {
                val done = CompletableFuture<Pair<String?, String?>>()
                bridge.transcribe(samples, language) { r, e -> done.complete(r to e) }
                val (json, error) = done.get(120, TimeUnit.SECONDS)
                println("whisper[$language]: $json")
                assertNull(error)
                val segments = Json.parseToJsonElement(assertNotNull(json)).jsonArray
                for (s in segments) {
                    val o = s.jsonObject
                    assertTrue(o.keys.containsAll(setOf("t0", "t1", "text")), "segment shape: $o")
                    assertTrue(o["t1"]!!.jsonPrimitive.content.toLong() >= o["t0"]!!.jsonPrimitive.content.toLong())
                }
                WhisperRecognizer.parseSegments(json) // the shared parser accepts it
            }
            val unsupported = CompletableFuture<String?>()
            bridge.transcribe(samples, "xx") { _, e -> unsupported.complete(e) }
            assertNotNull(unsupported.get(30, TimeUnit.SECONDS))
        } finally {
            bridge.unload()
        }
    }

    @Test
    fun whisperCancelReportsCancelled() {
        val bridge = loadedWhisper("whisperCancelReportsCancelled") ?: return
        try {
            // Cancelled while queued, then cancelled while (most likely) running inside whisper_full.
            for (delayMs in listOf(0L, 30L, 150L)) {
                val done = CompletableFuture<Pair<String?, String?>>()
                bridge.transcribe(toneThenSilence(seconds = 30), "en") { r, e -> done.complete(r to e) }
                if (delayMs > 0) Thread.sleep(delayMs)
                bridge.cancel()
                val (json, error) = done.get(120, TimeUnit.SECONDS)
                if (delayMs > 0 && json != null) {
                    // A fast GPU can finish 30 s of audio before the cancel lands: a complete result is then correct.
                    assertNull(error)
                    println("whisper finished within $delayMs ms; mid-run cancel not exercised on this machine")
                    continue
                }
                assertNull(json, "no result after cancel")
                assertTrue(LocalLlmBridge.isCancelled(error), "error is 'cancelled…': $error")
            }
            // A transcription issued after cancel() runs normally.
            val after = CompletableFuture<String?>()
            bridge.transcribe(toneThenSilence(seconds = 2), "en") { r, _ -> after.complete(r) }
            assertNotNull(after.get(120, TimeUnit.SECONDS))
        } finally {
            bridge.unload()
        }
    }

    // --- fixtures ------------------------------------------------------------------------------------------------

    private companion object {
        val PROMPT = listOf(ChatMessage(Role.USER, "Once upon a time"))
        const val TINY_URL = "https://huggingface.co/ggml-org/models/resolve/main/tinyllamas/stories260K.gguf"
        const val TINY_SHA256 = "270cba1bd5109f42d03350f60406024560464db173c0e387d91f0426d3bd256d"

        fun repoRoot(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                if (File(dir, "native/CMakeLists.txt").isFile) return dir
                dir = dir.parentFile
            }
            return File(System.getProperty("user.dir")).absoluteFile
        }

        fun tinyModelFile(): File = File(repoRoot(), "native/build/test-models/stories260K.gguf")

        @Synchronized
        fun tinyModel(): File? {
            val file = tinyModelFile()
            if (file.isFile && sha256(file) == TINY_SHA256) return file
            return try {
                file.parentFile.mkdirs()
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                val request = HttpRequest.newBuilder(URI(TINY_URL)).timeout(Duration.ofSeconds(120)).GET().build()
                val part = File(file.path + ".part")
                val response = client.send(request, HttpResponse.BodyHandlers.ofFile(part.toPath()))
                if (response.statusCode() != 200) error("HTTP ${response.statusCode()}")
                if (sha256(part) != TINY_SHA256) error("hash mismatch")
                if (!part.renameTo(file)) error("rename failed")
                file
            } catch (e: Exception) {
                println("download failed: ${e.message}")
                null
            }
        }

        fun whisperModel(): File? = listOf("ggml-tiny.bin", "ggml-base.bin")
            .map { File(repoRoot(), "content/models/$it") }
            .firstOrNull { it.isFile }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** A 440 Hz tone for the first second, silence after: 16 kHz mono floats. */
        fun toneThenSilence(seconds: Int): FloatArray = FloatArray(16_000 * seconds) { i ->
            if (i < 16_000) (0.3 * sin(2 * PI * 440 * i / 16_000.0)).toFloat() else 0f
        }
    }
}
