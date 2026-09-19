package app.tsumugi.media

import app.tsumugi.net.NetTimeouts
import app.tsumugi.net.tsumugiHttpClient
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.settings.DeviceSettings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An online example-sentence source (BRIEF_V2 §6.2). Results are shown live, never stored in packs. */
interface ExampleSource {
    /** Stable id, also the device-setting suffix. */
    val id: String

    /** Human name for the settings toggle and the result label. */
    val name: String

    @Throws(Exception::class)
    suspend fun search(word: String, limit: Int): List<SentenceHit>
}

sealed interface OnlineExamplesResult {
    /** The learner hasn't turned the source on (the default). */
    data object Disabled : OnlineExamplesResult

    data class Found(val hits: List<SentenceHit>, val fromSessionCache: Boolean) : OnlineExamplesResult

    /** Offline, timed out, or the service changed; the library and Tatoeba results are unaffected. */
    data class Failed(val reason: String) : OnlineExamplesResult
}

/**
 * The Immersion Kit public search API (v2, `GET https://apiv2.immersionkit.com/search?q=…`, no key), checked on
 * 2026-09-18 (docs/INTEGRATIONS.md). Each example has `sentence`, `translation`, `title` (deck slug), `id`,
 * `word_list` + `matched_indexes` (the matched word as token indexes), and `image` / `sound` (full URLs with
 * `showUrlInMedia=true`). Timeouts are [NetTimeouts.API] (rule 13).
 */
class ImmersionKitSource(
    engine: HttpClientEngine,
    private val baseUrl: String = BASE_URL,
) : ExampleSource {
    override val id = "immersionKit"
    override val name = "Immersion Kit"
    private val client = tsumugiHttpClient(engine, NetTimeouts.API)

    @Throws(Exception::class)
    override suspend fun search(word: String, limit: Int): List<SentenceHit> {
        val response = client.get("$baseUrl/search") {
            parameter("q", word)
            parameter("showUrlInMedia", "true")
        }
        if (!response.status.isSuccess()) throw IllegalStateException("Immersion Kit answered HTTP ${response.status.value}")
        return parse(response.bodyAsText(), limit)
    }

    companion object {
        const val BASE_URL = "https://apiv2.immersionkit.com"
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses a search response; examples without a sentence are skipped. */
        fun parse(body: String, limit: Int): List<SentenceHit> {
            val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
            val examples = root["examples"] as? JsonArray ?: return emptyList()
            return examples.asSequence().mapNotNull { it as? JsonObject }.mapNotNull { ex ->
                val sentence = ex.str("sentence")?.let(::normalizeNfc)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val words = (ex["word_list"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                val match = (ex["matched_indexes"] as? JsonArray)?.firstOrNull()?.let { runCatching { it.jsonObject }.getOrNull() }
                var span = -1 to -1
                if (match != null && words.isNotEmpty()) {
                    val index = match["index"]?.jsonPrimitive?.intOrNull ?: -1
                    val length = match["length"]?.jsonPrimitive?.intOrNull ?: 1
                    if (index in words.indices) {
                        // word_list tiles the sentence; the match covers `length` characters from that token's start.
                        val start = words.take(index).sumOf { it.length }
                        if (start + length <= sentence.length) span = start to start + length
                    }
                }
                SentenceHit(
                    source = SentenceSource.IMMERSION_KIT,
                    japanese = sentence,
                    english = ex.str("translation")?.takeIf { it.isNotBlank() },
                    highlightStart = span.first,
                    highlightEnd = span.second,
                    mediaTitle = ex.str("title")?.let(::deckTitle),
                    remoteId = ex.str("id"),
                    imageUrl = ex.str("image")?.takeIf { it.startsWith("https://") },
                    audioUrl = ex.str("sound")?.takeIf { it.startsWith("https://") },
                )
            }.take(limit).toList()
        }

        /** "my_neighbor_totoro" → "My Neighbor Totoro" (the API returns deck slugs). */
        fun deckTitle(slug: String): String =
            slug.split('_').filter { it.isNotEmpty() }.joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

/**
 * The optional online example source (BRIEF_V2 §6.2, DECISIONS D-162): OFF by default, switched on per device
 * (device setting `examples.<source id>`, rule 16). Results are kept in an in-memory cache for this app session only
 * (never written to the database or a pack) and nothing is fetched while the switch is off.
 */
class OnlineExamples(
    private val source: ExampleSource,
    private val deviceSettings: DeviceSettings,
    private val cacheSize: Int = 50,
) {
    private val lock = Mutex()
    private val cache = LinkedHashMap<String, List<SentenceHit>>()

    val sourceName: String get() = source.name

    @Throws(Exception::class)
    suspend fun enabled(): Boolean = deviceSettings.bool(settingKey(source.id), false)

    @Throws(Exception::class)
    suspend fun setEnabled(on: Boolean) {
        deviceSettings.put(settingKey(source.id), on.toString())
        if (!on) lock.withLock { cache.clear() }
    }

    /** Live results for [word], or Disabled / Failed. Never throws for network trouble. */
    @Throws(Exception::class)
    suspend fun search(word: String, limit: Int = 20): OnlineExamplesResult {
        if (!enabled()) return OnlineExamplesResult.Disabled
        val key = normalizeNfc(word.trim())
        if (key.isEmpty()) return OnlineExamplesResult.Found(emptyList(), false)
        lock.withLock { cache[key] }?.let { return OnlineExamplesResult.Found(it.take(limit), fromSessionCache = true) }
        return try {
            val hits = source.search(key, maxOf(limit, 20))
            lock.withLock {
                cache.remove(key)
                cache[key] = hits
                while (cache.size > cacheSize) cache.remove(cache.keys.first())
            }
            OnlineExamplesResult.Found(hits.take(limit), fromSessionCache = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            OnlineExamplesResult.Failed(e.message ?: "${source.name} didn't answer")
        }
    }

    companion object {
        fun settingKey(sourceId: String) = "examples.$sourceId"
    }
}
