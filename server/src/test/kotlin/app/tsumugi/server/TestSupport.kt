package app.tsumugi.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.assertEquals

fun testConfig(db: String = "sqlite:" + File.createTempFile("tsumugi", ".db").apply { deleteOnExit() }.path) =
    Config(database = db, jwtSecret = "x".repeat(40), rateLimitPerMinute = 10_000)

/** Runs the whole server in-process against [config] and hands the test a JSON client. */
fun serverTest(config: Config = testConfig(), block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) = testApplication {
    application { tsumugi(config, mailer = RecordingMailer) }
    val client = createClient { install(ContentNegotiation) { json(ServerJson) } }
    block(client)
}

object RecordingMailer : Mailer {
    val sent = mutableListOf<Pair<String, String>>()
    override fun sendVerification(email: String, link: String) {
        sent += email to link
    }
}

suspend fun HttpClient.postJson(path: String, body: Any, token: String? = null): HttpResponse = post(path) {
    contentType(ContentType.Application.Json)
    token?.let { bearerAuth(it) }
    setBody(body)
}

suspend fun HttpClient.getAuth(path: String, token: String): HttpResponse = get(path) { bearerAuth(token) }

suspend fun HttpClient.putJson(path: String, body: Any, token: String): HttpResponse = put(path) {
    contentType(ContentType.Application.Json)
    bearerAuth(token)
    setBody(body)
}

suspend fun HttpClient.patchJson(path: String, body: Any, token: String): HttpResponse = patch(path) {
    contentType(ContentType.Application.Json)
    bearerAuth(token)
    setBody(body)
}

suspend fun HttpClient.deleteAuth(path: String, token: String): HttpResponse = delete(path) { bearerAuth(token) }

suspend fun HttpClient.signUp(email: String, device: String = "phone", password: String = "correct horse battery"): TokenResponse {
    postJson("/v1/auth/register", RegisterRequest(email, password, email.substringBefore('@')))
    val response = postJson("/v1/auth/login", LoginRequest(email, password, device, "ios"))
    assertEquals(200, response.status.value, "login")
    return response.body()
}

suspend fun HttpClient.login(email: String, device: String, password: String = "correct horse battery"): TokenResponse =
    postJson("/v1/auth/login", LoginRequest(email, password, device, "android")).body()

fun reviewChange(id: String, ts: Long, deviceId: String) = Change(
    table = "review",
    key = id,
    op = "UPSERT",
    row = JsonObject(mapOf("id" to JsonPrimitive(id), "ts" to JsonPrimitive(ts), "rating" to JsonPrimitive(3))),
    updatedAt = ts,
    deviceId = deviceId,
)
