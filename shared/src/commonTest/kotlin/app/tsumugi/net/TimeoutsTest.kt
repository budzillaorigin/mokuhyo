package app.tsumugi.net

import app.tsumugi.ai.AiException
import app.tsumugi.ai.ChatMessage
import app.tsumugi.ai.CompletionRequest
import app.tsumugi.ai.OpenAICompatibleModel
import app.tsumugi.ai.Role
import app.tsumugi.ai.VoicevoxSynthesizer
import app.tsumugi.ai.WhisperEndpointRecognizer
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/** F-11 / CLAUDE.md rule 13: every client has a timeout, and a dead endpoint fails fast for a minute. */
class TimeoutsTest {
    private val request = CompletionRequest(listOf(ChatMessage(Role.USER, "hi")))
    private val short = NetTimeouts(connectMs = 5_000, requestMs = 300, socketMs = null)

    /** A server that accepts the connection and never answers in time. */
    private fun hanging(calls: MutableList<String> = mutableListOf()) = MockEngine { req ->
        calls += req.url.host
        delay(10_000)
        respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    @Test
    fun budgetsFollowRule13() {
        assertEquals(5_000, NetTimeouts.CHAT.connectMs)
        assertEquals(30_000, NetTimeouts.CHAT.requestMs)
        assertEquals(30_000, NetTimeouts.API.requestMs)
        // Downloads: no whole-request cap (a 2 GB model takes minutes), but 120 s of silence fails.
        assertNull(NetTimeouts.DOWNLOAD.requestMs)
        assertEquals(120_000, NetTimeouts.DOWNLOAD.socketMs)
        assertEquals(5_000, NetTimeouts.DOWNLOAD.connectMs)
        assertEquals(3_000, NetTimeouts.VOICEVOX_PROBE.requestMs)
        assertEquals(15_000, NetTimeouts.VOICEVOX_SYNTH.requestMs)
    }

    @Test
    fun chatRequestTimesOut() = runTest {
        withContext(Dispatchers.Default) {
            val model = OpenAICompatibleModel(hanging(), "http://box:11434", null, "m", timeouts = short)
            val started = TimeSource.Monotonic.markNow()
            assertFailsWith<AiException> { model.complete(request) }
            assertTrue(started.elapsedNow().inWholeMilliseconds < 5_000, "gave up after ${started.elapsedNow()}")
        }
    }

    @Test
    fun whisperEndpointTimesOut() = runTest {
        withContext(Dispatchers.Default) {
            val stt = WhisperEndpointRecognizer(hanging(), "http://stt:9000", null, timeouts = short)
            assertFailsWith<AiException> { stt.transcribe(ShortArray(1600), "ja") }
        }
    }

    @Test
    fun voicevoxProbeAndSynthesisAreBounded() = runTest {
        withContext(Dispatchers.Default) {
            val tts = VoicevoxSynthesizer(hanging(), "http://tts:50021", 3, timeouts = short, probeTimeouts = short)
            val started = TimeSource.Monotonic.markNow()
            assertFalse(tts.probe())
            assertFailsWith<AiException> { tts.synthesize("こんにちは") }
            assertTrue(started.elapsedNow().inWholeMilliseconds < 5_000, "gave up after ${started.elapsedNow()}")
        }
    }

    @Test
    fun unreachableEndpointFailsFastForSixtySeconds() = runTest {
        var now = 0L
        val health = EndpointHealth(nowMs = { now })
        val calls = mutableListOf<String>()
        withContext(Dispatchers.Default) {
            val model = OpenAICompatibleModel(hanging(calls), "http://box:11434", null, "m", health, short)
            assertFailsWith<AiException> { model.complete(request) }
            assertEquals(1, calls.size)

            // Within the minute: no network call at all, so the fallback engages immediately.
            now = 59_000
            val started = TimeSource.Monotonic.markNow()
            val cached = assertFailsWith<AiException> { model.complete(request) }
            assertTrue(started.elapsedNow().inWholeMilliseconds < 200)
            assertTrue("didn't answer recently" in cached.message.orEmpty(), cached.message)
            assertEquals(1, calls.size)

            // Other engines on the same host share the verdict.
            val stt = WhisperEndpointRecognizer(hanging(calls), "http://box:11434/v1", null, health = health, timeouts = short)
            assertFailsWith<AiException> { stt.transcribe(ShortArray(10), "ja") }
            assertEquals(1, calls.size)

            // After a minute the endpoint is tried again.
            now = 61_000
            assertFailsWith<AiException> { model.complete(request) }
            assertEquals(2, calls.size)
        }
    }

    @Test
    fun successClearsTheCache() {
        var now = 0L
        val health = EndpointHealth(nowMs = { now })
        health.markDown("http://box:11434/v1/chat/completions")
        assertTrue(health.unreachable("http://BOX:11434/v1") != null)
        assertNull(health.unreachable("http://other:11434/v1"))
        health.markUp("http://box:11434")
        assertNull(health.unreachable("http://box:11434/v1"))
    }
}
