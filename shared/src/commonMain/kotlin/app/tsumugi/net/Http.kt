package app.tsumugi.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.TimeSource

/**
 * Timeouts for one kind of network call (CLAUDE.md rule 13, D-050). `null` means "no limit of this kind".
 *
 * - [connectMs]: TCP/TLS connect. 5 s everywhere: a LAN server that doesn't answer in 5 s is off or asleep.
 * - [requestMs]: the whole call, from sending to the last byte of the response.
 * - [socketMs]: the longest gap between two packets. Long downloads use this instead of [requestMs], so a slow
 *   but live 2 GB download never times out while a stalled one does.
 */
data class NetTimeouts(val connectMs: Long, val requestMs: Long?, val socketMs: Long?) {
    companion object {
        /** LLM chat completions on the learner's own server. */
        val CHAT = NetTimeouts(connectMs = 5_000, requestMs = 30_000, socketMs = null)

        /** Small JSON APIs: WaniKani, sync, model lists, feeds, web pages. */
        val API = NetTimeouts(connectMs = 5_000, requestMs = 30_000, socketMs = null)

        /** Speech-to-text uploads: a recording plus server-side transcription. */
        val STT = NetTimeouts(connectMs = 5_000, requestMs = 60_000, socketMs = null)

        /** Model and pack downloads: no whole-request limit, 120 s without a byte fails. */
        val DOWNLOAD = NetTimeouts(connectMs = 5_000, requestMs = null, socketMs = 120_000)

        /** VOICEVOX reachability check (GET /version). */
        val VOICEVOX_PROBE = NetTimeouts(connectMs = 3_000, requestMs = 3_000, socketMs = null)

        /** VOICEVOX audio_query + synthesis of one sentence. */
        val VOICEVOX_SYNTH = NetTimeouts(connectMs = 3_000, requestMs = 15_000, socketMs = null)
    }
}

/** The one way shared code creates an [HttpClient]: `expectSuccess = false` and [HttpTimeout] always installed. */
fun tsumugiHttpClient(engine: HttpClientEngine, timeouts: NetTimeouts): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = timeouts.connectMs
        timeouts.requestMs?.let { requestTimeoutMillis = it }
        timeouts.socketMs?.let { socketTimeoutMillis = it }
    }
}

/**
 * Remembers which user-configured endpoints just failed to answer, so the next call fails fast and the fallback
 * (system recognizer, system voice, deterministic answer) engages immediately instead of waiting out another
 * timeout (BRIEF_V2 F-11). A host is "down" for [ttlMs] after a connection failure or timeout; any success, or
 * the learner pressing "Test connection", clears it. HTTP error statuses don't count: the server answered.
 */
@OptIn(ExperimentalAtomicApi::class)
class EndpointHealth(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val nowMs: () -> Long = monotonicMs(),
) {
    // Engines are called from several coroutines and threads: an immutable map swapped atomically.
    private val downSince = AtomicReference(emptyMap<String, Long>())

    /** Null when [endpoint] may be tried, else a message for the fallback reason. */
    fun unreachable(endpoint: String): String? {
        val key = key(endpoint)
        val since = synchronizedGet(key) ?: return null
        val age = nowMs() - since
        if (age >= ttlMs) {
            synchronizedRemove(key)
            return null
        }
        return "$key didn't answer recently; retrying in ${(ttlMs - age + 999) / 1000} s"
    }

    fun markDown(endpoint: String) = synchronizedPut(key(endpoint), nowMs())

    fun markUp(endpoint: String) = synchronizedRemove(key(endpoint))

    private fun synchronizedGet(k: String) = downSince.load()[k]
    private fun synchronizedPut(k: String, v: Long) = update { it + (k to v) }
    private fun synchronizedRemove(k: String) = update { it - k }

    private inline fun update(change: (Map<String, Long>) -> Map<String, Long>) {
        while (true) {
            val old = downSince.load()
            if (downSince.compareAndSet(old, change(old))) return
        }
    }

    companion object {
        const val DEFAULT_TTL_MS = 60_000L

        /** Scheme + host + port: every path on one server shares its health. */
        fun key(endpoint: String): String {
            val trimmed = endpoint.trim()
            val scheme = trimmed.substringBefore("://", "")
            val rest = trimmed.substringAfter("://")
            val authority = rest.substringBefore('/').substringAfter('@').lowercase()
            return if (scheme.isEmpty()) authority else "${scheme.lowercase()}://$authority"
        }

        private fun monotonicMs(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }
    }
}
