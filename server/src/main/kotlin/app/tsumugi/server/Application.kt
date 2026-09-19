package app.tsumugi.server

import com.auth0.jwt.JWT
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.http.withCharset
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.toByteArray
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlin.time.Duration.Companion.minutes

const val VERSION = "1.0.0"

fun main() {
    val config = Config.fromEnv()
    embeddedServer(Netty, port = config.port) { tsumugi(config) }.start(wait = true)
}

/** The whole server as a Ktor module, so tests can run it in-process with any [Config]. */
fun Application.tsumugi(config: Config, mailer: Mailer = config.smtp?.let(::SmtpMailer) ?: LogMailer()) {
    val db = Db(config)
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { db.close() }
    val auth = AuthService(db, config, mailer)
    val sync = SyncService(db, config)
    val accounts = AccountService(db, config)
    val passkeys = PasskeyService(db, config, auth)
    if (config.requireEmailVerification && config.smtp == null) {
        log.warn("Email verification is required but SMTP isn't configured: verification links are written to this log.")
    }

    install(ContentNegotiation) { json(ServerJson) }
    install(BodyLimit) { maxBytes = config.maxBodyBytes }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorBody(e.message, e.code)) }
        exception<SerializationException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorBody("malformed JSON: ${e.message}")) }
        exception<io.ktor.server.plugins.BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(e.cause?.message ?: e.message ?: "bad request"))
        }
    }
    install(Authentication) {
        jwt("access") {
            verifier(JWT.require(auth.algorithm).withIssuer(config.jwtIssuer).build())
            validate { cred -> cred.payload.subject?.let { JWTPrincipal(cred.payload) } }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorBody("missing or expired access token")) }
        }
    }
    install(RateLimit) {
        register(RATE_LIMIT) {
            rateLimiter(limit = config.rateLimitPerMinute, refillPeriod = 1.minutes)
            // Per user when a token is presented (decoded without verification — only used as a bucket key),
            // otherwise per client address.
            requestKey { call ->
                call.request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")
                    ?.let { runCatching { JWT.decode(it).subject }.getOrNull() }
                    ?: call.request.origin.remoteHost
            }
        }
    }

    routing {
        route("/v1") {
            get("/health") { call.respond(Health("ok", VERSION)) }

            rateLimit(RATE_LIMIT) {
                route("/auth") {
                    post("/register") { call.respond(HttpStatusCode.Created, RegisterResponse(auth.register(call.receive()))) }
                    get("/verify") {
                        // Opened in a browser from the email, so the answer is plain text.
                        val (status, text) = when (auth.verifyEmail(call.request.queryParameters["token"].orEmpty())) {
                            VerifyOutcome.VERIFIED -> HttpStatusCode.OK to "Email confirmed. You can go back to Tsumugi and sync."
                            VerifyOutcome.EXPIRED -> HttpStatusCode.Gone to "This link has expired. In Tsumugi, open Sync and tap Resend email."
                            VerifyOutcome.INVALID -> HttpStatusCode.NotFound to "Link invalid or already used."
                        }
                        call.respondText(text, ContentType.Text.Plain.withCharset(Charsets.UTF_8), status)
                    }
                    post("/login") { call.respond(auth.login(call.receive())) }
                    post("/refresh") { call.respond(auth.refresh(call.receive<RefreshRequest>().refreshToken)) }
                    post("/logout") {
                        auth.logout(call.receive<RefreshRequest>().refreshToken)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    post("/passkey/login/options") { call.respond(passkeys.loginOptions(call.receive())) }
                    post("/passkey/login/verify") { call.respond(passkeys.loginVerify(call.receive())) }
                    authenticate("access") {
                        post("/verify/resend") {
                            auth.resendVerification(call.userId())
                            call.respond(HttpStatusCode.NoContent)
                        }
                        post("/passkey/register/options") { call.respond(passkeys.registerOptions(call.userId())) }
                        post("/passkey/register/verify") {
                            passkeys.registerVerify(call.userId(), call.receive())
                            call.respond(HttpStatusCode.NoContent)
                        }
                    }
                }

                authenticate("access") {
                    get("/account") { call.respond(accounts.account(call.userId())) }
                    patch("/account") { call.respond(accounts.patch(call.userId(), call.receive())) }

                    get("/devices") { call.respond(accounts.devices(call.userId())) }
                    delete("/devices/{id}") {
                        accounts.deleteDevice(call.userId(), call.parameters["id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    put("/devices/{id}/packs") {
                        auth.requireVerified(call.userId())
                        accounts.setPacks(call.userId(), call.parameters["id"]!!, call.receive<PacksRequest>().packs)
                        call.respond(HttpStatusCode.NoContent)
                    }

                    post("/sync/push") {
                        auth.requireVerified(call.userId())
                        call.respond(sync.push(call.userId(), call.deviceId(), call.receive<PushRequest>().changes))
                    }
                    get("/sync/pull") {
                        auth.requireVerified(call.userId())
                        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: SyncService.DEFAULT_PULL
                        call.respond(sync.pull(call.userId(), since, limit))
                    }

                    put("/blobs/{id}") {
                        auth.requireVerified(call.userId())
                        val bytes = call.receiveChannel().toByteArray()
                        accounts.putBlob(call.userId(), call.parameters["id"]!!, call.request.contentType().toString(), bytes)
                        call.respond(HttpStatusCode.NoContent)
                    }
                    get("/blobs/{id}") {
                        auth.requireVerified(call.userId())
                        val (type, bytes) = accounts.getBlob(call.userId(), call.parameters["id"]!!)
                        call.respondBytes(bytes, runCatching { ContentType.parse(type) }.getOrDefault(ContentType.Application.OctetStream))
                    }
                    delete("/blobs/{id}") {
                        auth.requireVerified(call.userId())
                        accounts.deleteBlob(call.userId(), call.parameters["id"]!!)
                        call.respond(HttpStatusCode.NoContent)
                    }

                    get("/leaderboard") {
                        auth.requireVerified(call.userId())
                        call.respond(sync.leaderboard(call.request.queryParameters["period"] ?: "week"))
                    }
                }
            }
        }
    }
}

private val RATE_LIMIT = RateLimitName("api")

private fun ApplicationCall.userId(): String = principal<JWTPrincipal>()?.payload?.subject ?: unauthorized()

private fun ApplicationCall.deviceId(): String =
    principal<JWTPrincipal>()?.payload?.getClaim(AuthService.CLAIM_DEVICE)?.asString() ?: unauthorized()

/**
 * Rejects request bodies over the configured size: up front from Content-Length, and while reading for bodies
 * streamed without one (chunked), so a client can never make the server buffer more than the limit.
 */
class BodyLimitConfig {
    var maxBytes: Long = 8L * 1024 * 1024
}

val BodyLimit = createApplicationPlugin("BodyLimit", ::BodyLimitConfig) {
    val max = pluginConfig.maxBytes
    onCall { call ->
        val method = call.request.local.method
        if (method != HttpMethod.Post && method != HttpMethod.Put && method != HttpMethod.Patch) return@onCall
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > max) call.respond(HttpStatusCode.PayloadTooLarge, ErrorBody("request body larger than $max bytes"))
    }
    onCallReceive { _ ->
        transformBody { data ->
            val bytes = data.readRemaining(max + 1).readByteArray()
            if (bytes.size > max) throw ApiException(413, "request body larger than $max bytes")
            ByteReadChannel(bytes)
        }
    }
}
