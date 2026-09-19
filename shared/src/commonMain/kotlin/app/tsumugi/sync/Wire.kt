package app.tsumugi.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** One change record, exactly as on the wire (docs/SYNC_PROTOCOL.md "Change record"). */
@Serializable
data class Change(
    val table: String,
    /** Primary key; composite keys joined with U+001F. */
    val key: String,
    /** "UPSERT" or "DELETE". */
    val op: String,
    /** Full row (column → value); absent for DELETE and when [sealed] is used. */
    val row: JsonObject? = null,
    /** Base64 of nonce ‖ XChaCha20-Poly1305 ciphertext of the row JSON (end-to-end encryption on). */
    val sealed: String? = null,
    val updatedAt: Long,
    val deviceId: String,
    /** Assigned by the server; absent on push. */
    val seq: Long? = null,
) {
    companion object {
        const val UPSERT = "UPSERT"
        const val DELETE = "DELETE"
    }
}

@Serializable data class PushRequest(val changes: List<Change>)

@Serializable data class PushResponse(val accepted: Int, val lastSeq: Long)

@Serializable data class PullResponse(val changes: List<Change>, val lastSeq: Long, val hasMore: Boolean)

@Serializable data class RegisterRequest(val email: String, val password: String, val displayName: String? = null)

@Serializable data class RegisterResponse(val userId: String)

@Serializable data class LoginRequest(val email: String, val password: String, val deviceName: String, val platform: String)

@Serializable data class TokenResponse(val accessToken: String, val refreshToken: String, val deviceId: String? = null, val expiresIn: Long = 0)

@Serializable data class RefreshRequest(val refreshToken: String)

@Serializable
data class SyncAccountInfo(
    val userId: String,
    val email: String,
    val displayName: String? = null,
    val e2eEnabled: Boolean = false,
    val e2eSalt: String? = null,
    val leaderboardOptIn: Boolean = false,
    /** Whether the account's email is confirmed. Servers before Phase 14 don't send it; they never required it. */
    val emailVerified: Boolean = true,
    /** Whether this server refuses sync until the email is confirmed (D-310). */
    val emailVerificationRequired: Boolean = false,
) {
    /** True when sync is blocked until the learner opens the link in their email. */
    val needsEmailVerification: Boolean get() = emailVerificationRequired && !emailVerified
}

@Serializable
data class AccountPatch(
    val displayName: String? = null,
    val e2eEnabled: Boolean? = null,
    val e2eSalt: String? = null,
    val leaderboardOptIn: Boolean? = null,
)

@Serializable data class DeviceInfo(val id: String, val name: String, val platform: String, val lastSeenAt: Long? = null, val packs: Map<String, String> = emptyMap())

@Serializable data class DevicePacks(val packs: Map<String, String>)

@Serializable data class LeaderboardRow(val displayName: String, val reviews: Int, val streak: Int)

internal val SyncJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/** What the sync engine needs from a server: the real [HttpSyncClient], or an in-memory fake in tests. */
interface SyncTransport {
    @Throws(Exception::class)
    suspend fun push(changes: List<Change>): PushResponse
    @Throws(Exception::class)
    suspend fun pull(since: Long, limit: Int): PullResponse
}

/**
 * A failed sync request. [status] is the HTTP status, and [code] the server's machine-readable reason when it sent
 * one (docs/SYNC_PROTOCOL.md "Errors").
 */
open class SyncException(message: String, val status: Int? = null, val code: String? = null) : Exception(message)

/**
 * The server requires a confirmed email before this account can sync (403 `email_unverified`, D-310). The apps show
 * "Check your email to finish setting up sync" with a Resend button ([SyncAccount.resendVerification]).
 */
class EmailNotVerifiedException : SyncException("Check your email to finish setting up sync", 403, CODE) {
    companion object {
        const val CODE = "email_unverified"
    }
}

/** Stable error codes the sync server sends in `{"error", "code"}` bodies. */
object SyncErrorCodes {
    const val EMAIL_UNVERIFIED = EmailNotVerifiedException.CODE
    /** 429 on resend: an email went out moments ago. */
    const val RESEND_TOO_SOON = "resend_too_soon"
    /** 409 on resend: the email is already confirmed. */
    const val ALREADY_VERIFIED = "already_verified"
}

@Serializable internal data class ErrorBody(val error: String? = null, val code: String? = null)

/** The typed exception for a non-2xx response with body [text]. */
internal fun syncFailure(what: String, status: Int, text: String): SyncException {
    val body = runCatching { SyncJson.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
    return when (body?.code) {
        SyncErrorCodes.EMAIL_UNVERIFIED -> EmailNotVerifiedException()
        else -> SyncException("$what failed: $status ${body?.error ?: text}", status, body?.code)
    }
}

/**
 * The server's per-user blob store (docs/SYNC_PROTOCOL.md `/blobs/{id}`): opaque bytes, 20 MB each, a per-user
 * quota. Used only by the opt-in recordings/pictures sync (DECISIONS D-111). Ids: 1–128 of [A-Za-z0-9._-].
 */
interface BlobStore {
    @Throws(Exception::class)
    suspend fun putBlob(id: String, contentType: String, bytes: ByteArray)

    /** The blob, or null when there is none with this id. */
    @Throws(Exception::class)
    suspend fun getBlob(id: String): ByteArray?

    @Throws(Exception::class)
    suspend fun deleteBlob(id: String)

    /** Server ids of this account's devices (each one publishes its own manifest). */
    @Throws(Exception::class)
    suspend fun deviceIds(): List<String>
}
