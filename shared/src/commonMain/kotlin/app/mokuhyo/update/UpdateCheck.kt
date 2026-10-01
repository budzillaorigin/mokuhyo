package app.mokuhyo.update

import app.mokuhyo.net.NetTimeouts
import app.mokuhyo.net.mokuhyoHttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One GitHub release, as the releases API lists it. */
@Serializable
data class Release(
    @SerialName("tag_name") val tag: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("html_url") val url: String = "",
)

sealed interface UpdateResult {
    data object UpToDate : UpdateResult
    data class Available(val release: Release, val version: String) : UpdateResult
    data class Failed(val message: String) : UpdateResult
}

/**
 * The optional update check (BRIEF §3 rule 2, §9 Settings → Privacy & updates): off until the learner turns it on;
 * one GET to the GitHub releases list, no identifiers sent, nothing downloaded. It only tells the learner a newer
 * version exists and links the release page.
 */
class UpdateChecker(engine: HttpClientEngine, private val url: String = DEFAULT_URL) {
    private val http = mokuhyoHttpClient(engine, NetTimeouts.API)

    suspend fun check(current: String): UpdateResult = try {
        val r = http.get(url) { header("Accept", "application/vnd.github+json") }
        when {
            r.status == HttpStatusCode.NotFound -> UpdateResult.Failed("the release list isn't public yet (private repository)")
            !r.status.isSuccess() -> UpdateResult.Failed("Release list answered ${r.status.value}")
            else -> newest(json.decodeFromString(ListSerializer(Release.serializer()), r.bodyAsText()), current)
                ?.let { (rel, v) -> UpdateResult.Available(rel, v) } ?: UpdateResult.UpToDate
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        UpdateResult.Failed(e.message ?: e::class.simpleName ?: "network error")
    }

    companion object {
        const val DEFAULT_URL = "https://api.github.com/repos/budzillaorigin/mokuhyo/releases?per_page=30"
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The newest app release above [current], or null. Only `vMAJOR.MINOR.PATCH[-pre]` tags count (content-pack
         * releases such as `packs-2026-10-01` don't); drafts never count; pre-releases count while the learner is on a
         * 0.x or pre-release build, since every release so far is one.
         */
        fun newest(releases: List<Release>, current: String): Pair<Release, String>? {
            val cur = Version.parse(current) ?: return null
            val includePre = cur.major == 0 || cur.pre != null
            return releases.asSequence()
                .filter { !it.draft && (includePre || !it.prerelease) }
                .mapNotNull { r -> Version.parse(r.tag)?.let { r to it } }
                .filter { it.second > cur }
                .maxByOrNull { it.second }
                ?.let { (r, v) -> r to v.toString() }
        }
    }
}

/** Semantic version with an optional pre-release tag; `1.2.0-rc.1` < `1.2.0`. Build metadata and "-dev" sort low. */
data class Version(val major: Int, val minor: Int, val patch: Int, val pre: String?) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }).takeIf { it != 0 } ?: when {
            pre == other.pre -> 0
            pre == null -> 1
            other.pre == null -> -1
            else -> comparePre(pre, other.pre)
        }

    override fun toString() = "$major.$minor.$patch" + (pre?.let { "-$it" } ?: "")

    companion object {
        private val RE = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(s: String): Version? = RE.matchEntire(s.trim())?.destructured?.let { (a, b, c, p) ->
            Version(a.toInt(), b.toInt(), c.toInt(), p.ifEmpty { null })
        }

        private fun comparePre(a: String, b: String): Int {
            val x = a.split('.')
            val y = b.split('.')
            for (i in 0 until minOf(x.size, y.size)) {
                val n = x[i].toIntOrNull()
                val m = y[i].toIntOrNull()
                val c = when {
                    n != null && m != null -> n.compareTo(m)
                    n != null -> -1
                    m != null -> 1
                    else -> x[i].compareTo(y[i])
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }
}
