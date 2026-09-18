package app.tsumugi.sync

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.platform.Secrets
import io.ktor.client.engine.HttpClientEngine

/** Access/refresh tokens in the platform keychain/keystore (CLAUDE.md rule 6). */
class SecretTokenStore(private val secrets: Secrets) : TokenStore {
    override var accessToken: String?
        get() = secrets.get(ACCESS)
        set(value) = if (value == null) secrets.remove(ACCESS) else secrets.put(ACCESS, value)
    override var refreshToken: String?
        get() = secrets.get(REFRESH)
        set(value) = if (value == null) secrets.remove(REFRESH) else secrets.put(REFRESH, value)

    private companion object {
        const val ACCESS = "sync.accessToken"
        const val REFRESH = "sync.refreshToken"
    }
}

/**
 * The learner's sync account: server URL (any self-hosted instance), sign-in, and the optional end-to-end
 * passphrase. The E2E key is derived on demand and kept in memory only; a verifier in app_meta detects a wrong
 * passphrase without contacting the server.
 */
class SyncAccount(
    private val db: TsumugiDatabase,
    secrets: Secrets,
    private val engineFactory: () -> HttpClientEngine,
    private val keyParams: E2eKeys.Params = E2eKeys.Params(),
) {
    private val tokens = SecretTokenStore(secrets)
    private val meta get() = db.metaQueries
    private var cachedClient: HttpSyncClient? = null

    /** In-memory sealer once the passphrase has been entered this session. */
    var sealer: Sealer? = null
        private set

    val baseUrl: String? get() = meta.get(BASE_URL).executeAsOneOrNull()
    val isSignedIn: Boolean get() = baseUrl != null && tokens.refreshToken != null
    val e2eEnabled: Boolean get() = meta.get(E2E_VERIFIER).executeAsOneOrNull() != null

    fun client(): HttpSyncClient? {
        val url = baseUrl ?: return null
        return cachedClient ?: HttpSyncClient(engineFactory(), url, tokens).also { cachedClient = it }
    }

    suspend fun register(serverUrl: String, email: String, password: String, displayName: String?): String {
        useServer(serverUrl)
        return client()!!.register(RegisterRequest(email, password, displayName)).userId
    }

    suspend fun login(serverUrl: String, email: String, password: String, deviceName: String, platform: String): SyncAccountInfo {
        useServer(serverUrl)
        val c = client()!!
        val t = c.login(LoginRequest(email, password, deviceName, platform))
        t.deviceId?.let { meta.put(SERVER_DEVICE_ID, it) }
        return c.account()
    }

    suspend fun logout() {
        client()?.logout()
        sealer = null
    }

    /** Turns on end-to-end encryption with a new salt. Existing synced data is re-pushed sealed by the caller. */
    suspend fun enableE2e(passphrase: String) {
        val c = client() ?: throw SyncException("Not signed in")
        val salt = E2eKeys.newSalt()
        val key = E2eKeys.derive(passphrase, salt, keyParams)
        c.updateAccount(AccountPatch(e2eEnabled = true, e2eSalt = salt))
        meta.put(E2E_VERIFIER, E2eKeys.verifier(key))
        sealer = E2eSealer(key)
    }

    /**
     * Unlocks E2E for this session. Returns false for a wrong passphrase (checked against the local verifier;
     * on a new device, the first pull fails to decrypt instead).
     */
    suspend fun unlockE2e(passphrase: String): Boolean {
        val c = client() ?: throw SyncException("Not signed in")
        val salt = c.account().e2eSalt ?: return false
        val key = E2eKeys.derive(passphrase, salt, keyParams)
        val verifier = meta.get(E2E_VERIFIER).executeAsOneOrNull()
        if (verifier != null && !E2eKeys.matches(key, verifier)) return false
        if (verifier == null) meta.put(E2E_VERIFIER, E2eKeys.verifier(key))
        sealer = E2eSealer(key)
        return true
    }

    private fun useServer(url: String) {
        if (url != baseUrl) cachedClient = null
        meta.put(BASE_URL, url.trim().trimEnd('/'))
    }

    companion object {
        const val BASE_URL = "sync.baseUrl"
        const val SERVER_DEVICE_ID = "sync.serverDeviceId"
        const val E2E_VERIFIER = "sync.e2eVerifier"
    }
}
