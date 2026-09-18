package app.tsumugi.sync

import app.tsumugi.net.NetTimeouts
import app.tsumugi.net.tsumugiHttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer

/** Where access/refresh tokens live (Keychain/Keystore via [app.tsumugi.platform.Secrets] in the apps). */
interface TokenStore {
    var accessToken: String?
    var refreshToken: String?
}

/**
 * Ktor client for the sync server (docs/SYNC_PROTOCOL.md "Endpoints"). A 401 triggers one refresh with the
 * rotating refresh token and a retry.
 */
class HttpSyncClient(
    engine: HttpClientEngine,
    private val baseUrl: String,
    private val tokens: TokenStore,
) : SyncTransport, BlobStore {
    private val http = tsumugiHttpClient(engine, NetTimeouts.API)

    /** Blobs are up to 20 MB: no whole-request limit, but 120 s without a byte fails (rule 13). */
    private val blobHttp = tsumugiHttpClient(engine, NetTimeouts.DOWNLOAD)
    private val root get() = baseUrl.trimEnd('/') + "/v1"

    // --- Auth ---------------------------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun register(request: RegisterRequest): RegisterResponse =
        call(HttpMethod.Post, "/auth/register", RegisterRequest.serializer(), request, RegisterResponse.serializer(), auth = false)

    @Throws(Exception::class)
    suspend fun login(request: LoginRequest): TokenResponse =
        call(HttpMethod.Post, "/auth/login", LoginRequest.serializer(), request, TokenResponse.serializer(), auth = false)
            .also { save(it) }

    @Throws(Exception::class)
    suspend fun refresh(): TokenResponse {
        val refresh = tokens.refreshToken ?: throw SyncException("Not signed in", 401)
        return call(HttpMethod.Post, "/auth/refresh", RefreshRequest.serializer(), RefreshRequest(refresh), TokenResponse.serializer(), auth = false, retry = false)
            .also { save(it) }
    }

    @Throws(Exception::class)
    suspend fun logout() {
        tokens.refreshToken?.let { refresh ->
            runCatching { send(HttpMethod.Post, "/auth/logout", SyncJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(refresh)), auth = false, retry = false) }
        }
        tokens.accessToken = null
        tokens.refreshToken = null
    }

    // --- Account and devices ------------------------------------------------------------------------------

    @Throws(Exception::class)
    suspend fun account(): SyncAccountInfo = call(HttpMethod.Get, "/account", null, null, SyncAccountInfo.serializer())

    @Throws(Exception::class)
    suspend fun updateAccount(patch: AccountPatch): SyncAccountInfo =
        call(HttpMethod.Patch, "/account", AccountPatch.serializer(), patch, SyncAccountInfo.serializer())

    @Throws(Exception::class)
    suspend fun devices(): List<DeviceInfo> = call(HttpMethod.Get, "/devices", null, null, ListSerializer(DeviceInfo.serializer()))

    @Throws(Exception::class)
    suspend fun removeDevice(id: String) {
        check(send(HttpMethod.Delete, "/devices/$id", null))
    }

    @Throws(Exception::class)
    suspend fun putPacks(deviceId: String, packs: Map<String, String>) {
        check(send(HttpMethod.Put, "/devices/$deviceId/packs", SyncJson.encodeToString(DevicePacks.serializer(), DevicePacks(packs))))
    }

    @Throws(Exception::class)
    suspend fun leaderboard(period: String = "week"): List<LeaderboardRow> =
        call(HttpMethod.Get, "/leaderboard?period=$period", null, null, ListSerializer(LeaderboardRow.serializer()))

    // --- Sync ---------------------------------------------------------------------------------------------

    @Throws(Exception::class)
    override suspend fun push(changes: List<Change>): PushResponse =
        call(HttpMethod.Post, "/sync/push", PushRequest.serializer(), PushRequest(changes), PushResponse.serializer())

    @Throws(Exception::class)
    override suspend fun pull(since: Long, limit: Int): PullResponse =
        call(HttpMethod.Get, "/sync/pull?since=$since&limit=$limit", null, null, PullResponse.serializer())

    // --- Blobs (opt-in recordings and pictures sync, DECISIONS D-111) ---------------------------------------

    @Throws(Exception::class)
    override suspend fun putBlob(id: String, contentType: String, bytes: ByteArray) {
        val response = blob(HttpMethod.Put, id) { setBody(ByteArrayContent(bytes, ContentType.parse(contentType))) }
        check(response)
    }

    @Throws(Exception::class)
    override suspend fun getBlob(id: String): ByteArray? {
        val response = blob(HttpMethod.Get, id) {}
        if (response.status.value == 404) return null
        check(response)
        return response.bodyAsBytes()
    }

    @Throws(Exception::class)
    override suspend fun deleteBlob(id: String) {
        val response = blob(HttpMethod.Delete, id) {}
        if (response.status.value != 404) check(response)
    }

    @Throws(Exception::class)
    override suspend fun deviceIds(): List<String> = devices().map { it.id }

    private suspend fun blob(method: HttpMethod, id: String, body: io.ktor.client.request.HttpRequestBuilder.() -> Unit): HttpResponse {
        suspend fun once(): HttpResponse = blobHttp.request("$root/blobs/$id") {
            this.method = method
            tokens.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            body()
        }
        val first = once()
        if (first.status.value == 401 && tokens.refreshToken != null) {
            refresh()
            return once()
        }
        return first
    }

    // --- Plumbing -----------------------------------------------------------------------------------------

    private fun save(t: TokenResponse) {
        tokens.accessToken = t.accessToken
        tokens.refreshToken = t.refreshToken
    }

    private suspend fun <Req, Res> call(
        method: HttpMethod,
        path: String,
        requestSerializer: KSerializer<Req>?,
        body: Req?,
        responseSerializer: KSerializer<Res>,
        auth: Boolean = true,
        retry: Boolean = true,
    ): Res {
        val json = if (requestSerializer != null && body != null) SyncJson.encodeToString(requestSerializer, body) else null
        val response = send(method, path, json, auth, retry)
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) throw SyncException("${method.value} $path failed: ${response.status.value} $text", response.status.value)
        return SyncJson.decodeFromString(responseSerializer, text)
    }

    private suspend fun send(method: HttpMethod, path: String, json: String?, auth: Boolean = true, retry: Boolean = true): HttpResponse {
        @Throws(Exception::class)
        suspend fun once(): HttpResponse = http.request(root + path) {
            this.method = method
            if (auth) tokens.accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (json != null) setBody(TextContent(json, ContentType.Application.Json))
        }
        val first = once()
        if (first.status.value == 401 && auth && retry && tokens.refreshToken != null) {
            refresh()
            return once()
        }
        return first
    }

    private suspend fun check(response: HttpResponse) {
        if (response.status.value !in 200..299) throw SyncException("Request failed: ${response.status.value} ${response.bodyAsText()}", response.status.value)
    }
}
