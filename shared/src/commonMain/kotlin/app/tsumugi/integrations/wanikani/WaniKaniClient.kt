package app.tsumugi.integrations.wanikani

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WaniKaniException(val status: Int, message: String) : Exception(message)

/** A cached GET response, revalidated with If-None-Match / If-Modified-Since. */
@Serializable
data class WkCachedResponse(val etag: String? = null, val lastModified: String? = null, val body: String)

/** One fully paginated collection plus the timestamp to use as the next `updated_after` cursor. */
data class WkPage<T>(val items: List<WkResource<T>>, val dataUpdatedAt: String?)

/**
 * WaniKani API v2 client (BRIEF §9.1). Talks only to api.wanikani.com with the user's own token.
 *
 * - `Authorization: Bearer <token>` and `Wanikani-Revision: 20170710` on every request;
 * - collections are followed through `pages.next_url`;
 * - at most [requestsPerMinute] requests per rolling minute (waits instead of failing), and a 429 waits until
 *   the `RateLimit-Reset` time before retrying;
 * - GETs are revalidated with the stored ETag / Last-Modified; a 304 returns the cached body. The cache is
 *   handed to [onCacheUpdated] so callers can persist it.
 */
class WaniKaniClient(
    private val token: String,
    engine: HttpClientEngine,
    private val clock: Clock = Clock.System,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    initialCache: Map<String, WkCachedResponse> = emptyMap(),
    private val onCacheUpdated: (Map<String, WkCachedResponse>) -> Unit = {},
    private val baseUrl: String = BASE_URL,
    private val requestsPerMinute: Int = 60,
) {
    private val http = HttpClient(engine) { expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val cache = initialCache.toMutableMap()
    private val limiterLock = Mutex()
    private val recent = ArrayDeque<Instant>()

    suspend fun user(): WkUser = decode(WkResource.serializer(WkUser.serializer()), get("$baseUrl/user")).data

    suspend fun subjects(ids: Collection<Long>): WkPage<WkSubject> =
        if (ids.isEmpty()) WkPage(emptyList(), null) else collection("subjects", WkSubject.serializer(), "ids" to ids.joinToString(","))

    suspend fun assignments(updatedAfter: String? = null): WkPage<WkAssignment> =
        collection("assignments", WkAssignment.serializer(), "started" to "true", "updated_after" to updatedAfter)

    suspend fun reviewStatistics(updatedAfter: String? = null): WkPage<WkReviewStatistic> =
        collection("review_statistics", WkReviewStatistic.serializer(), "updated_after" to updatedAfter)

    suspend fun studyMaterials(updatedAfter: String? = null): WkPage<WkStudyMaterial> =
        collection("study_materials", WkStudyMaterial.serializer(), "updated_after" to updatedAfter)

    suspend fun levelProgressions(): WkPage<WkLevelProgression> =
        collection("level_progressions", WkLevelProgression.serializer())

    /**
     * POST /reviews. Returns the HTTP status: 2xx = accepted, 401/403 = the token can't write (read-only),
     * 422 = WaniKani rejected this review (e.g. not available for review), anything else = try again later.
     */
    suspend fun postReview(subjectId: Long, incorrectMeaning: Int, incorrectReading: Int, createdAt: Instant): Int {
        val body = json.encodeToString(
            WkReviewBody.serializer(),
            WkReviewBody(WkReviewPost(subjectId, incorrectMeaning, incorrectReading, createdAt.toString())),
        )
        return send(HttpMethod.Post, "$baseUrl/reviews", body).status.value
    }

    /** PUT /study_materials/:id with the user's own notes/synonyms. Returns the HTTP status. */
    suspend fun updateStudyMaterial(id: Long, update: WkStudyMaterialUpdate): Int {
        val body = json.encodeToString(WkStudyMaterialBody.serializer(), WkStudyMaterialBody(update))
        return send(HttpMethod.Put, "$baseUrl/study_materials/$id", body).status.value
    }

    fun close() = http.close()

    private suspend fun <T> collection(path: String, serializer: KSerializer<T>, vararg params: Pair<String, String?>): WkPage<T> {
        val query = params.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=${v!!.encodeURLParameter()}" }
        var url: String? = "$baseUrl/$path" + if (query.isEmpty()) "" else "?$query"
        val items = ArrayList<WkResource<T>>()
        var updatedAt: String? = null
        val pageSerializer = WkCollection.serializer(serializer)
        while (url != null) {
            val page = decode(pageSerializer, get(url))
            if (updatedAt == null) updatedAt = page.dataUpdatedAt
            items += page.data
            url = page.pages.nextUrl
        }
        return WkPage(items, updatedAt)
    }

    private suspend fun get(url: String): String {
        val cached = cache[url]
        val response = send(HttpMethod.Get, url, null) {
            cached?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
            cached?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
        }
        return when (val status = response.status.value) {
            304 -> cached?.body ?: throw WaniKaniException(304, "304 without a cached response for $url")
            in 200..299 -> {
                val body = response.bodyAsText()
                val etag = response.headers[HttpHeaders.ETag]
                val lastModified = response.headers[HttpHeaders.LastModified]
                if ((etag != null || lastModified != null) && body.length <= MAX_CACHED_BODY) {
                    cache[url] = WkCachedResponse(etag, lastModified, body)
                    onCacheUpdated(cache.toMap())
                }
                body
            }
            401 -> throw WaniKaniException(status, "WaniKani rejected the API token")
            else -> throw WaniKaniException(status, "WaniKani returned HTTP $status for $url")
        }
    }

    /** One request with rate limiting and 429 retries. */
    private suspend fun send(
        method: HttpMethod,
        url: String,
        body: String?,
        extra: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        repeat(MAX_RETRIES) {
            acquire()
            val response = http.request(url) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
                header("Wanikani-Revision", API_REVISION)
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                extra()
            }
            if (response.status.value != 429) return response
            sleep(retryDelay(response))
        }
        throw WaniKaniException(429, "WaniKani rate limit: gave up after $MAX_RETRIES attempts")
    }

    private fun retryDelay(response: HttpResponse): Duration {
        val reset = response.headers["RateLimit-Reset"]?.toLongOrNull() ?: return DEFAULT_RETRY
        val wait = Instant.fromEpochSeconds(reset) - clock.now()
        return wait.coerceIn(1.seconds, 1.minutes)
    }

    /** Rolling-window limiter: waits until fewer than [requestsPerMinute] requests happened in the last minute. */
    private suspend fun acquire() = limiterLock.withLock {
        while (true) {
            val now = clock.now()
            while (recent.isNotEmpty() && now - recent.first() >= 1.minutes) recent.removeFirst()
            if (recent.size < requestsPerMinute) {
                recent.addLast(now)
                return@withLock
            }
            sleep(recent.first() + 1.minutes - now)
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, body: String): T = json.decodeFromString(serializer, body)

    companion object {
        const val BASE_URL = "https://api.wanikani.com/v2"
        const val API_REVISION = "20170710"
        private const val MAX_RETRIES = 4
        private const val MAX_CACHED_BODY = 256 * 1024
        private val DEFAULT_RETRY = 10.seconds
    }
}
