package app.mokuhyo.ai

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BRIEF_PHASE8 N-00b: trained context and model name come from the GGUF header; the context is capped at it. */
class GgufReaderTest {
    /** A minimal GGUF v3 header: a tokenizer array first (to prove skipping works), then the keys the app reads. */
    private fun gguf(name: String, arch: String, ctx: Long): ByteArray {
        val out = ByteArrayOutputStream()
        fun le(n: Int, f: (ByteBuffer) -> Unit) = out.write(ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).also(f).array())
        fun u32(v: Long) = le(4) { it.putInt(v.toInt()) }
        fun u64(v: Long) = le(8) { it.putLong(v) }
        fun str(s: String) = s.toByteArray().let { u64(it.size.toLong()); out.write(it) }
        out.write("GGUF".toByteArray()); u32(3); u64(0); u64(4)
        str("tokenizer.ggml.tokens"); u32(9); u32(8); u64(3); str("<s>"); str("a"); str("ありがとう")
        str("general.architecture"); u32(8); str(arch)
        str("general.name"); u32(8); str(name)
        str("$arch.context_length"); u32(4); u32(ctx)
        return out.toByteArray()
    }

    @Test
    fun readsNameArchitectureAndTrainedContext() {
        val info = GgufReader.read(gguf("EuroLLM 9B Instruct", "llama", 4096).inputStream())
        assertEquals(ModelFileInfo("EuroLLM 9B Instruct", "llama", 4096), info)
        assertEquals(null, GgufReader.read("not a gguf".byteInputStream()))
    }

    @Test
    fun contextIsCappedAndLabelComesFromEachFileInSequence(): Unit = runBlocking {
        val dir = Files.createTempDirectory("gguf").toFile()
        val euro = File(dir, "eurollm.gguf").apply { writeBytes(gguf("EuroLLM 9B Instruct", "llama", 4096)) }
        val mistral = File(dir, "mistral.gguf").apply { writeBytes(gguf("Mistral Small 3.2 24B", "llama", 131072)) }
        val bridge = FakeLlmBridge()
        val slot = LoadedModelSlot()
        val tier = testModel.copy(contextSize = 8192, name = "Phi-4-mini (configured tier)")
        val a = LocalLlamaModel(bridge, tier, euro.absolutePath, slot, GgufReader::read)
        assertEquals(4096, a.contextSize, "8192 configured, 4096 trained -> 4096")
        assertEquals("on-device EuroLLM 9B Instruct", a.complete(CompletionRequest(listOf(ChatMessage(Role.USER, "hi")))).engine)
        assertEquals(euro.absolutePath to 4096, bridge.loadedWith)
        val b = LocalLlamaModel(bridge, tier, mistral.absolutePath, slot, GgufReader::read)
        assertEquals(8192, b.contextSize, "trained context above the configured one keeps the configured value")
        assertEquals("on-device Mistral Small 3.2 24B", b.complete(CompletionRequest(listOf(ChatMessage(Role.USER, "hi")))).engine)
        assertEquals(mistral.absolutePath to 8192, bridge.loadedWith, "switching files reloads with the second file")
        dir.deleteRecursively()
    }

    /** A 30-turn conversation on a 4096-token model never sends more than the context allows. */
    @Test
    fun longConversationsAreTrimmedToTheTrainedContext() {
        val system = ChatMessage(Role.SYSTEM, "You are a conversation partner. ".repeat(40))
        val turns = (1..30).flatMap { i ->
            listOf(ChatMessage(Role.USER, "Turn $i: " + "これは長い発話です。".repeat(40)), ChatMessage(Role.ASSISTANT, "Reply $i: " + "そうですね。".repeat(40)))
        }
        val maxTokens = 600
        for (n in 1..turns.size) {
            val fitted = ContextWindow.fit(listOf(system) + turns.take(n), 4096, maxTokens)
            assertTrue(ContextWindow.estimateTokens(fitted) + maxTokens <= 4096, "turn $n: ${ContextWindow.estimateTokens(fitted)} tokens")
            assertEquals(system, fitted.first())
            assertEquals(turns[n - 1], fitted.last(), "the current turn is always kept")
        }
    }

    /** The real files the owner's smoke run used (skipped where they aren't downloaded). */
    @Test
    fun realModelFiles() {
        val dir = File(System.getProperty("user.home"), "mokuhyo/tools/.cache/models")
        val euro = File(dir, "EuroLLM-9B-Instruct-Q4_K_M.gguf")
        val phi = File(dir, "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf")
        if (!euro.isFile || !phi.isFile) return println("SKIP realModelFiles: models not downloaded")
        val e = GgufReader.read(euro.absolutePath)
        val p = GgufReader.read(phi.absolutePath)
        println("gguf: $e / $p")
        assertEquals(4096, e?.contextLength, "EuroLLM-9B n_ctx_train")
        assertTrue(e?.name.orEmpty().contains("EuroLLM", ignoreCase = true))
        assertTrue(p?.name.orEmpty().contains("Phi", ignoreCase = true))
    }
}
