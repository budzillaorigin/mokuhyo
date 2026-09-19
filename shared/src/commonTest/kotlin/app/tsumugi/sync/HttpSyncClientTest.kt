package app.tsumugi.sync

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The client speaks exactly the wire format of docs/SYNC_PROTOCOL.md. */
class HttpSyncClientTest {

    private class MemoryTokens(override var accessToken: String? = null, override var refreshToken: String? = null) : TokenStore

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun pushAndPullWireFormat() = runTest {
        val requests = ArrayList<Pair<String, String>>()
        val engine = MockEngine { req ->
            val body = (req.body as? TextContent)?.text.orEmpty()
            requests += "${req.method.value} ${req.url.encodedPathAndQuery} ${req.headers[HttpHeaders.Authorization]}" to body
            when (req.url.encodedPath) {
                "/v1/sync/push" -> respond("""{"accepted":1,"lastSeq":7}""", HttpStatusCode.OK, json)
                else -> respond(
                    """{"changes":[{"table":"setting","key":"k","op":"UPSERT","row":{"key":"k","value":"v","updated_at":5},"updatedAt":5,"deviceId":"d2","seq":7}],"lastSeq":7,"hasMore":false}""",
                    HttpStatusCode.OK, json,
                )
            }
        }
        val client = HttpSyncClient(engine, "https://sync.example/", MemoryTokens("access", "refresh"))
        val change = Change("note", "k:水", Change.UPSERT, row = JsonObject(mapOf("item_id" to JsonPrimitive("k:水"))), updatedAt = 42, deviceId = "d1")
        assertEquals(PushResponse(1, 7), client.push(listOf(change)))
        val pulled = client.pull(since = 3, limit = 100)
        assertEquals(7L, pulled.changes.single().seq)
        assertEquals("d2", pulled.changes.single().deviceId)

        val (pushLine, pushBody) = requests[0]
        assertEquals("POST /v1/sync/push Bearer access", pushLine)
        val sent = Json.parseToJsonElement(pushBody).jsonObject["changes"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("table", "key", "op", "row", "updatedAt", "deviceId"), sent.keys, "no seq/sealed on push")
        assertEquals("UPSERT", sent["op"]!!.jsonPrimitive.content)
        assertEquals("GET /v1/sync/pull?since=3&limit=100 Bearer access", requests[1].first)
    }

    @Test
    fun expiredAccessTokenRefreshesOnceAndRetries() = runTest {
        var calls = 0
        val engine = MockEngine { req ->
            calls++
            when {
                req.url.encodedPath == "/v1/auth/refresh" -> {
                    assertTrue((req.body as TextContent).text.contains("\"refreshToken\":\"old-refresh\""))
                    respond("""{"accessToken":"new-access","refreshToken":"new-refresh","expiresIn":900}""", HttpStatusCode.OK, json)
                }
                req.headers[HttpHeaders.Authorization] == "Bearer old-access" -> respond("", HttpStatusCode.Unauthorized)
                else -> respond("""{"userId":"u1","email":"a@b.c","e2eEnabled":true,"e2eSalt":"c2FsdA=="}""", HttpStatusCode.OK, json)
            }
        }
        val tokens = MemoryTokens("old-access", "old-refresh")
        val account = HttpSyncClient(engine, "https://sync.example", tokens).account()
        assertEquals("c2FsdA==", account.e2eSalt)
        assertEquals("new-access", tokens.accessToken)
        assertEquals("new-refresh", tokens.refreshToken)
        assertEquals(3, calls)
    }

    @Test
    fun loginStoresTokensAndSendsNoBearer() = runTest {
        val engine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            assertFalse(req.headers.contains(HttpHeaders.Authorization))
            val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            assertEquals(setOf("email", "password", "deviceName", "platform"), body.keys)
            respond("""{"accessToken":"a","refreshToken":"r","deviceId":"dev-1","expiresIn":900}""", HttpStatusCode.OK, json)
        }
        val tokens = MemoryTokens()
        val t = HttpSyncClient(engine, "https://sync.example", tokens).login(LoginRequest("a@b.c", "pw", "iPhone", "ios"))
        assertEquals("dev-1", t.deviceId)
        assertEquals("a", tokens.accessToken)
    }

    @Test
    fun unverifiedEmailBecomesATypedError() = runTest {
        val engine = MockEngine { req ->
            when (req.url.encodedPath) {
                "/v1/auth/verify/resend" -> respond(
                    """{"error":"a verification email was sent recently; try again in 90 s","code":"resend_too_soon"}""",
                    HttpStatusCode.TooManyRequests, json,
                )
                else -> respond("""{"error":"confirm your email address to sync","code":"email_unverified"}""", HttpStatusCode.Forbidden, json)
            }
        }
        val client = HttpSyncClient(engine, "https://sync.example", MemoryTokens("a", "r"))
        val pushError = assertFailsWith<EmailNotVerifiedException> { client.push(emptyList()) }
        assertEquals(403, pushError.status)
        assertEquals(SyncErrorCodes.EMAIL_UNVERIFIED, pushError.code)
        assertFailsWith<EmailNotVerifiedException> { client.getBlob("rec-1") }

        val resend = assertFailsWith<SyncException> { client.resendVerification() }
        assertFalse(resend is EmailNotVerifiedException)
        assertEquals(SyncErrorCodes.RESEND_TOO_SOON, resend.code)
        assertEquals(429, resend.status)
    }

    @Test
    fun otherErrorsKeepTheirStatusWithoutACode() = runTest {
        val engine = MockEngine { respond("plain failure", HttpStatusCode.InternalServerError) }
        val error = assertFailsWith<SyncException> { HttpSyncClient(engine, "https://sync.example", MemoryTokens("a", "r")).pull(0, 10) }
        assertEquals(500, error.status)
        assertEquals(null, error.code)
        assertTrue("plain failure" in error.message.orEmpty())
    }

    @Test
    fun accountReportsWhetherVerificationBlocksSync() = runTest {
        var body = """{"userId":"u","email":"a@b.c","emailVerified":false,"emailVerificationRequired":true}"""
        val engine = MockEngine { respond(body, HttpStatusCode.OK, json) }
        val client = HttpSyncClient(engine, "https://sync.example", MemoryTokens("a", "r"))
        assertTrue(client.account().needsEmailVerification)
        body = """{"userId":"u","email":"a@b.c"}"""
        assertFalse(client.account().needsEmailVerification, "an older server never required it")
    }
}
