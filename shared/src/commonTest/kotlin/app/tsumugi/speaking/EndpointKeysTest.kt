package app.tsumugi.speaking

import app.tsumugi.ai.ChatMessage
import app.tsumugi.ai.CompletionRequest
import app.tsumugi.ai.Role
import app.tsumugi.platform.Secrets
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F-12 / CLAUDE.md rule 14: each endpoint gets only its own key. */
class EndpointKeysTest {
    private class MemorySecrets(val map: MutableMap<String, String> = mutableMapOf()) : Secrets {
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
    }

    /** Authorization header seen per host ("" = none). */
    private val auth = mutableMapOf<String, MutableList<String>>()
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val engine = MockEngine { req ->
        auth.getOrPut(req.url.host) { mutableListOf() } += req.headers[HttpHeaders.Authorization].orEmpty()
        when {
            req.url.encodedPath.endsWith("/chat/completions") ->
                respond("""{"choices":[{"message":{"content":"ok"}}]}""", HttpStatusCode.OK, json)
            req.url.encodedPath.endsWith("/audio/transcriptions") -> respond("""{"text":"はい"}""", HttpStatusCode.OK, json)
            req.url.encodedPath.endsWith("/audio_query") -> respond("""{"speedScale":1.0}""", HttpStatusCode.OK, json)
            else -> respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
        }
    }
    private val config = AiConfig(
        llm = LlmEngine.ENDPOINT, endpointUrl = "http://llm.lan:11434", endpointModel = "qwen",
        stt = SttEngine.WHISPER_ENDPOINT, sttEndpointUrl = "http://stt.lan:9000",
        tts = TtsEngine.VOICEVOX, voicevoxUrl = "http://tts.lan:50021",
    )

    private suspend fun callAll(engines: EndpointEngines) {
        engines.llm(config)!!.complete(CompletionRequest(listOf(ChatMessage(Role.USER, "hi"))))
        engines.recognizer(config)!!.transcribe(ShortArray(160), "ja")
        engines.synthesizer(config)!!.synthesize("はい")
    }

    @Test
    fun llmKeyNeverReachesSpeechEndpoints() = runTest {
        val secrets = MemorySecrets(mutableMapOf(EndpointEngines.LLM_KEY to "llm-secret"))
        callAll(EndpointEngines({ engine }, secrets))
        assertEquals(listOf("Bearer llm-secret"), auth.getValue("llm.lan").toList())
        assertEquals(listOf(""), auth.getValue("stt.lan").toList())
        assertEquals(listOf("", ""), auth.getValue("tts.lan").toList())
    }

    @Test
    fun eachEndpointGetsItsOwnKey() = runTest {
        val secrets = MemorySecrets(
            mutableMapOf(
                EndpointEngines.LLM_KEY to "llm-secret",
                EndpointEngines.STT_KEY to "stt-secret",
                EndpointEngines.TTS_KEY to "tts-secret",
            ),
        )
        callAll(EndpointEngines({ engine }, secrets))
        assertEquals(listOf("Bearer llm-secret"), auth.getValue("llm.lan").toList())
        assertEquals(listOf("Bearer stt-secret"), auth.getValue("stt.lan").toList())
        assertEquals(listOf("Bearer tts-secret", "Bearer tts-secret"), auth.getValue("tts.lan").toList())
    }

    @Test
    fun blankUrlsBuildNothing() {
        val engines = EndpointEngines({ engine }, MemorySecrets())
        val empty = AiConfig()
        assertNull(engines.llm(empty))
        assertNull(engines.recognizer(empty))
        assertNull(engines.synthesizer(empty))
    }
}
