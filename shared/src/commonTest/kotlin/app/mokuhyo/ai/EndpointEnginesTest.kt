package app.mokuhyo.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointEnginesTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val schema = JsonSchema.Obj(listOf("ok" to JsonSchema.Bool))
    private val request = CompletionRequest(listOf(ChatMessage(Role.USER, "hi")), jsonSchema = schema)

    private fun chatReply(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(String.serializer(), content)}}}],"usage":{"completion_tokens":7}}"""

    @Test
    fun sendsJsonSchemaAndParsesReply() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val engine = MockEngine { req ->
            assertEquals("http://box:11434/v1/chat/completions", req.url.toString())
            assertEquals("Bearer sk-local", req.headers[HttpHeaders.Authorization])
            bodies += Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
            respond(chatReply("{\"ok\":true}"), HttpStatusCode.OK, jsonHeaders)
        }
        val model = OpenAICompatibleModel(engine, "http://box:11434/", "sk-local", "mistral-nemo:12b")
        val result = model.complete(request)
        assertEquals("{\"ok\":true}", result.text)
        assertEquals(7, result.tokens)
        assertEquals("endpoint mistral-nemo:12b @ box", result.engine)
        val rf = bodies.single()["response_format"]!!.jsonObject
        assertEquals("json_schema", rf["type"]!!.jsonPrimitive.content)
        assertEquals(schema.toJson(), rf["json_schema"]!!.jsonObject["schema"])
        assertEquals("mistral-nemo:12b", bodies.single()["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun stepsDownStructuredOutputModes() = runTest {
        val formats = mutableListOf<String?>()
        val engine = MockEngine { req ->
            val body = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
            val type = body["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content
            formats += type
            if (type != null) {
                respond("""{"error":"response_format not supported"}""", HttpStatusCode.BadRequest, jsonHeaders)
            } else {
                respond(chatReply("{\"ok\":false}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val model = OpenAICompatibleModel(engine, "http://box:1234/v1", null, "m")
        model.complete(request)
        assertEquals(listOf("json_schema", "json_object", null), formats)
        model.complete(request) // remembers the prompt-only mode
        assertEquals(listOf("json_schema", "json_object", null, null), formats)
    }

    @Test
    fun noAuthHeaderWithoutKeyAndServerErrorsThrow() = runTest {
        val engine = MockEngine { req ->
            assertNull(req.headers[HttpHeaders.Authorization])
            respond("oops", HttpStatusCode.InternalServerError)
        }
        assertFailsWith<AiException> { OpenAICompatibleModel(engine, "http://box", "", "m").complete(request) }
    }

    @Test
    fun probeListsModels() = runTest {
        val engine = MockEngine { req ->
            assertEquals("http://box:1234/v1/models", req.url.toString())
            respond("""{"object":"list","data":[{"id":"mistral-nemo:12b"},{"id":"llama3.1:8b"}]}""", HttpStatusCode.OK, jsonHeaders)
        }
        assertEquals(listOf("mistral-nemo:12b", "llama3.1:8b"), OpenAICompatibleModel(engine, "http://box:1234", null, "m").probe())
    }

    @Test
    fun normalizesBaseUrls() {
        assertEquals("http://a/v1", OpenAICompatibleModel.normalize("http://a"))
        assertEquals("http://a/v1", OpenAICompatibleModel.normalize(" http://a/v1/ "))
    }

    @Test
    fun whisperEndpointSendsMultipartWav() = runTest {
        val engine = MockEngine { req ->
            assertEquals("http://box:8000/v1/audio/transcriptions", req.url.toString())
            val body = req.body.toByteArray().decodeToString()
            assertTrue(req.body.contentType.toString().startsWith("multipart/form-data"), req.body.contentType.toString())
            assertTrue("verbose_json" in body)
            assertTrue("name=\"language\"" in body && "\r\n\r\nja\r\n" in body)
            assertTrue("filename=\"speech.wav\"" in body && "RIFF" in body)
            respond(
                """{"text":"こんにちは。元気です。","segments":[{"start":0.0,"end":1.25,"text":"こんにちは。"},{"start":1.25,"end":2.5,"text":"元気です。"}]}""",
                HttpStatusCode.OK,
                jsonHeaders,
            )
        }
        val t = WhisperEndpointRecognizer(engine, "http://box:8000", null).transcribe(ShortArray(160))
        assertEquals("こんにちは。元気です。", t.text)
        assertEquals(listOf(TranscriptSegment(0, 1250, "こんにちは。"), TranscriptSegment(1250, 2500, "元気です。")), t.segments)
        assertEquals("endpoint whisper-1", t.engine)
    }

    @Test
    fun voicevoxQueriesThenSynthesizes() = runTest {
        val wav = byteArrayOf(82, 73, 70, 70)
        val calls = mutableListOf<String>()
        val engine = MockEngine { req ->
            calls += req.url.encodedPath
            when (req.url.encodedPath) {
                "/audio_query" -> {
                    assertEquals("こんにちは", req.url.parameters["text"])
                    assertEquals("3", req.url.parameters["speaker"])
                    respond("""{"accent_phrases":[],"speedScale":1.0,"pitchScale":0.0}""", HttpStatusCode.OK, jsonHeaders)
                }
                "/synthesis" -> {
                    val q = Json.parseToJsonElement(req.body.toByteArray().decodeToString()).jsonObject
                    assertEquals(0.8, q["speedScale"]!!.jsonPrimitive.content.toDouble())
                    assertEquals("3", req.url.parameters["speaker"])
                    respond(wav, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/wav"))
                }
                else -> error("unexpected ${req.url}")
            }
        }
        val audio = VoicevoxSynthesizer(engine, "http://box:50021/", speaker = 3).synthesize("こんにちは", speed = 0.8)
        assertContentEquals(wav, audio)
        assertEquals(listOf("/audio_query", "/synthesis"), calls)
    }

    @Test
    fun voicevoxDownIsAnAiException() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }
        assertFailsWith<AiException> { VoicevoxSynthesizer(engine, "http://box:50021", 1).synthesize("あ") }
    }
}
