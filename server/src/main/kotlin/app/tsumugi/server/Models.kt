package app.tsumugi.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Wire types for docs/SYNC_PROTOCOL.md v1. */

val ServerJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

@Serializable data class RegisterRequest(val email: String, val password: String, val displayName: String? = null)
@Serializable data class RegisterResponse(val userId: String)
@Serializable data class LoginRequest(val email: String, val password: String, val deviceName: String, val platform: String)
@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String, val deviceId: String, val expiresIn: Long)
@Serializable data class RefreshRequest(val refreshToken: String)

@Serializable
data class Account(
    val userId: String,
    val email: String,
    val displayName: String?,
    val e2eEnabled: Boolean,
    val e2eSalt: String?,
    val leaderboardOptIn: Boolean,
    /** Whether the email address is confirmed. */
    val emailVerified: Boolean = false,
    /** Whether this server refuses sync until it is ([Config.requireEmailVerification]). */
    val emailVerificationRequired: Boolean = false,
)

@Serializable
data class AccountPatch(
    val displayName: String? = null,
    val e2eEnabled: Boolean? = null,
    val e2eSalt: String? = null,
    val leaderboardOptIn: Boolean? = null,
)

@Serializable data class Device(val id: String, val name: String, val platform: String, val lastSeenAt: Long, val packs: Map<String, String>)
@Serializable data class PacksRequest(val packs: Map<String, String> = emptyMap())

/** One change record. `row` (clear) or `sealed` (E2E ciphertext) is stored and returned verbatim. */
@Serializable
data class Change(
    val table: String,
    val key: String,
    val op: String,
    val row: JsonObject? = null,
    val sealed: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    val seq: Long? = null,
)

@Serializable data class PushRequest(val changes: List<Change>)
@Serializable data class PushResponse(val accepted: Int, val lastSeq: Long)
@Serializable data class PullResponse(val changes: List<Change>, val lastSeq: Long, val hasMore: Boolean)

@Serializable data class LeaderboardEntry(val displayName: String, val reviews: Int, val streak: Int)
@Serializable data class Health(val status: String, val version: String)
/** Every error response. [code] is a stable machine-readable reason for the ones clients act on (see [ErrorCodes]). */
@Serializable data class ErrorBody(val error: String, val code: String? = null)

class ApiException(val status: Int, override val message: String, val code: String? = null) : RuntimeException(message)

/** Stable error codes (docs/SYNC_PROTOCOL.md "Errors"). */
object ErrorCodes {
    /** 403 on sync, blobs, packs and leaderboard: the account's email isn't confirmed yet. */
    const val EMAIL_UNVERIFIED = "email_unverified"
    /** 429 on resend: a verification email went out less than the resend interval ago. */
    const val RESEND_TOO_SOON = "resend_too_soon"
    /** 409 on resend: nothing to do. */
    const val ALREADY_VERIFIED = "already_verified"
}

fun badRequest(message: String): Nothing = throw ApiException(400, message)
fun unauthorized(message: String = "unauthorized"): Nothing = throw ApiException(401, message)
fun notFound(message: String = "not found"): Nothing = throw ApiException(404, message)
