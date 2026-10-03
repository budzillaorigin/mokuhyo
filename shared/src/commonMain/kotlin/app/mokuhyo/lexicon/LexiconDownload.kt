package app.mokuhyo.lexicon

import app.mokuhyo.net.NetTimeouts
import app.mokuhyo.net.mokuhyoHttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable

/**
 * "Import lexicon update" from a URL (BRIEF_PHASE8 C-04): one explicit, learner-started GET of a package file — never
 * automatic, never in the background. Connect 5 s, whole request 120 s (CLAUDE.md rule 9), at most [MAX_BYTES];
 * cancelling the calling coroutine cancels the download.
 */
class LexiconDownload(private val engine: HttpClientEngine) {
    suspend fun fetch(url: String): String {
        require(url.startsWith("https://") || url.startsWith("http://")) { "enter an http(s) address" }
        val client = mokuhyoHttpClient(engine, NetTimeouts(connectMs = 5_000, requestMs = 120_000, socketMs = null))
        try {
            val response = client.get(url)
            require(response.status.isSuccess()) { "the server answered ${response.status.value}" }
            val channel = response.bodyAsChannel()
            val out = ArrayList<Byte>()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = channel.readAvailable(buf)
                if (n < 0) break
                for (i in 0 until n) out += buf[i]
                require(out.size <= MAX_BYTES) { "the file is larger than ${MAX_BYTES / (1024 * 1024)} MB; not a lexicon package" }
            }
            return out.toByteArray().decodeToString()
        } finally {
            client.close()
        }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}
