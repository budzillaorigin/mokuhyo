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
)

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

class SyncException(message: String, val status: Int? = null) : Exception(message)
