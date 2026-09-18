package app.tsumugi.integrations.notion

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.IntegrationKind
import app.tsumugi.domain.Stage
import app.tsumugi.net.NetTimeouts
import app.tsumugi.net.tsumugiHttpClient
import app.tsumugi.platform.Secrets
import app.tsumugi.srs.StudyItem
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class NotionException(val status: Int, message: String) : Exception(message)

/**
 * Minimal Notion API client (BRIEF_V2 G-09, docs/INTEGRATIONS.md). Talks only to https://api.notion.com with the
 * learner's integration token (rule 14: the token never goes anywhere else). Requests are spaced to Notion's
 * average of 3 per second, and a 429 waits for `Retry-After` once before retrying.
 */
class NotionClient(
    private val token: String,
    engine: HttpClientEngine,
    private val sleep: suspend (Duration) -> Unit = { delay(it) },
    private val minInterval: Duration = 350.milliseconds,
) {
    private val http = tsumugiHttpClient(engine, NetTimeouts.API)
    private var lastCall: kotlin.time.TimeMark? = null

    /** The database's title and property names/types, to check the mapping before a push. */
    @Throws(Exception::class)
    suspend fun database(databaseId: String): NotionDatabase {
        val o = call(HttpMethod.Get, "/v1/databases/${clean(databaseId)}", null)
        val title = o["title"]?.jsonArray?.joinToString("") { it.jsonObject["plain_text"]?.jsonPrimitive?.contentOrNull.orEmpty() }.orEmpty()
        val props = o["properties"]?.jsonObject.orEmpty().mapValues { (_, v) -> v.jsonObject["type"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        return NotionDatabase(clean(databaseId), title, props)
    }

    /** Page id of the row whose rich-text property [property] equals [value], or null. */
    @Throws(Exception::class)
    suspend fun findPage(databaseId: String, property: String, value: String): String? {
        val body = buildJsonObject {
            putJsonObject("filter") {
                put("property", property)
                putJsonObject("rich_text") { put("equals", value) }
            }
            put("page_size", 1)
        }
        val o = call(HttpMethod.Post, "/v1/databases/${clean(databaseId)}/query", body)
        return o["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    @Throws(Exception::class)
    suspend fun createPage(databaseId: String, properties: JsonObject): String {
        val body = buildJsonObject {
            putJsonObject("parent") { put("database_id", clean(databaseId)) }
            put("properties", properties)
        }
        return call(HttpMethod.Post, "/v1/pages", body)["id"]?.jsonPrimitive?.contentOrNull
            ?: throw NotionException(0, "Notion returned no page id")
    }

    @Throws(Exception::class)
    suspend fun updatePage(pageId: String, properties: JsonObject) {
        call(HttpMethod.Patch, "/v1/pages/$pageId", buildJsonObject { put("properties", properties) })
    }

    private suspend fun call(method: HttpMethod, path: String, body: JsonObject?, retried: Boolean = false): JsonObject {
        pace()
        val response = http.request(BASE_URL + path) {
            this.method = method
            header(HttpHeaders.Authorization, "Bearer $token")
            header(VERSION_HEADER, API_VERSION)
            if (body != null) setBody(TextContent(body.toString(), ContentType.Application.Json))
        }
        val text = response.bodyAsText()
        val status = response.status.value
        if (status == 429 && !retried) {
            val wait = response.headers["Retry-After"]?.toDoubleOrNull()?.coerceIn(0.5, 60.0) ?: 1.0
            sleep((wait * 1000).toLong().milliseconds)
            return call(method, path, body, retried = true)
        }
        if (status !in 200..299) {
            val message = runCatching { Json.parseToJsonElement(text).jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw NotionException(status, "Notion ${method.value} $path failed ($status): ${message ?: text.take(200)}")
        }
        return Json.parseToJsonElement(text).jsonObject
    }

    private suspend fun pace() {
        val mark = lastCall
        if (mark != null) {
            val left = minInterval - mark.elapsedNow()
            if (left.isPositive()) sleep(left)
        }
        lastCall = kotlin.time.TimeSource.Monotonic.markNow()
    }

    companion object {
        /** The only host the Notion token is ever sent to. */
        const val BASE_URL = "https://api.notion.com"
        const val VERSION_HEADER = "Notion-Version"
        const val API_VERSION = "2022-06-28"

        /** Accepts a database URL or id with or without dashes; returns the 32-hex id. */
        fun clean(idOrUrl: String): String {
            val tail = idOrUrl.trim().substringBefore('?').substringAfterLast('/').substringAfterLast('-')
            val hex = if (tail.length == 32 && tail.all { it.isLetterOrDigit() }) tail else idOrUrl.filter { it.isLetterOrDigit() }.takeLast(32)
            return hex.lowercase()
        }
    }
}

data class NotionDatabase(val id: String, val title: String, val properties: Map<String, String>)

/** Which Notion databases to fill (device-local, in the `integration` row for NOTION). */
@Serializable
data class NotionConfig(val itemsDatabaseId: String? = null, val statsDatabaseId: String? = null)

/** One day of study for the stats database. */
data class NotionDayStats(val date: String, val reviews: Int, val accuracy: Double?, val streak: Int, val minutes: Int)

data class NotionPushResult(val created: Int, val updated: Int, val failed: List<String>)

/**
 * Pushes study items and daily stats to the learner's Notion databases (BRIEF_V2 G-09, DECISIONS D-119). The
 * mapping (property names and types) is in docs/INTEGRATIONS.md; rows are matched by the "Tsumugi ID" property so a
 * second push updates instead of duplicating. The token lives in the keychain ([TOKEN_KEY]).
 */
class NotionExport(
    private val db: TsumugiDatabase,
    private val secrets: Secrets,
    private val client: (String) -> NotionClient,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val isConnected: Boolean get() = secrets.get(TOKEN_KEY) != null

    /** Stores the token and database ids after checking both databases have the mapped properties. */
    @Throws(Exception::class)
    suspend fun connect(token: String, itemsDatabaseId: String?, statsDatabaseId: String?): List<String> {
        val c = client(token.trim())
        val problems = ArrayList<String>()
        itemsDatabaseId?.takeIf { it.isNotBlank() }?.let { problems += missing(c.database(it), ITEM_PROPERTIES) }
        statsDatabaseId?.takeIf { it.isNotBlank() }?.let { problems += missing(c.database(it), STATS_PROPERTIES) }
        if (problems.isEmpty()) {
            secrets.put(TOKEN_KEY, token.trim())
            val config = NotionConfig(itemsDatabaseId?.takeIf { it.isNotBlank() }?.let(NotionClient::clean), statsDatabaseId?.takeIf { it.isNotBlank() }?.let(NotionClient::clean))
            withContext(Dispatchers.IO) { db.userQueries.putIntegration(IntegrationKind.NOTION.name, json.encodeToString(NotionConfig.serializer(), config), null, null) }
        }
        return problems
    }

    @Throws(Exception::class)
    suspend fun disconnect() {
        secrets.remove(TOKEN_KEY)
        withContext(Dispatchers.IO) { db.userQueries.removeIntegration(IntegrationKind.NOTION.name) }
    }

    @Throws(Exception::class)
    suspend fun config(): NotionConfig? = withContext(Dispatchers.IO) {
        db.userQueries.integration(IntegrationKind.NOTION.name).executeAsOneOrNull()?.config?.let {
            runCatching { json.decodeFromString(NotionConfig.serializer(), it) }.getOrNull()
        }
    }

    /** Creates or updates one row per item. [stages] maps item id → stage (SrsRepository.stages()). */
    @Throws(Exception::class)
    suspend fun pushItems(items: List<StudyItem>, stages: Map<String, Stage>, onProgress: (Int, Int) -> Unit = { _, _ -> }): NotionPushResult {
        val dbId = config()?.itemsDatabaseId ?: throw NotionException(0, "Choose a Notion database for items first")
        return push(dbId, items.map { it.id to itemProperties(it, stages[it.id]) }, onProgress)
    }

    @Throws(Exception::class)
    suspend fun pushStats(days: List<NotionDayStats>, onProgress: (Int, Int) -> Unit = { _, _ -> }): NotionPushResult {
        val dbId = config()?.statsDatabaseId ?: throw NotionException(0, "Choose a Notion database for daily stats first")
        return push(dbId, days.map { "day:${it.date}" to statsProperties(it) }, onProgress)
    }

    private suspend fun push(dbId: String, rows: List<Pair<String, JsonObject>>, onProgress: (Int, Int) -> Unit): NotionPushResult {
        val token = secrets.get(TOKEN_KEY) ?: throw NotionException(401, "Connect Notion first")
        val c = client(token)
        var created = 0
        var updated = 0
        val failed = ArrayList<String>()
        rows.forEachIndexed { i, (id, props) ->
            try {
                val page = c.findPage(dbId, ID_PROPERTY, id)
                if (page == null) { c.createPage(dbId, props); created++ } else { c.updatePage(page, props); updated++ }
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotionException) {
                if (e.status == 401 || e.status == 403 || e.status == 404) throw e // wrong token or database: stop
                failed += "$id: ${e.message}"
            }
            onProgress(i + 1, rows.size)
        }
        withContext(Dispatchers.IO) {
            val row = db.userQueries.integration(IntegrationKind.NOTION.name).executeAsOneOrNull()
            if (row != null) db.userQueries.putIntegration(row.kind, row.config, clock.now().toEpochMilliseconds(), row.cursor)
        }
        return NotionPushResult(created, updated, failed)
    }

    companion object {
        /** Keychain key of the Notion integration token (rule 14: sent only to api.notion.com). */
        const val TOKEN_KEY = "notion.token"
        const val ID_PROPERTY = "Tsumugi ID"

        /** Property → Notion type the items database must have (docs/INTEGRATIONS.md). */
        val ITEM_PROPERTIES = mapOf(
            "Name" to "title", ID_PROPERTY to "rich_text", "Reading" to "rich_text", "Meaning" to "rich_text",
            "Kind" to "select", "Stage" to "select", "Level" to "number", "JLPT" to "select", "Source" to "select",
        )
        val STATS_PROPERTIES = mapOf(
            "Name" to "title", ID_PROPERTY to "rich_text", "Date" to "date", "Reviews" to "number",
            "Accuracy" to "number", "Streak" to "number", "Minutes" to "number",
        )

        internal fun missing(db: NotionDatabase, wanted: Map<String, String>): List<String> =
            wanted.mapNotNull { (name, type) ->
                val actual = db.properties[name]
                when {
                    actual == null -> "\"${db.title}\" has no \"$name\" property ($type)"
                    actual != type -> "\"${db.title}\": \"$name\" is $actual, expected $type"
                    else -> null
                }
            }

        internal fun itemProperties(item: StudyItem, stage: Stage?): JsonObject = buildJsonObject {
            put("Name", title(item.primaryText))
            put(ID_PROPERTY, text(item.id))
            put("Reading", text(item.reading.orEmpty()))
            put("Meaning", text(item.meanings.joinToString("; ")))
            put("Kind", select(item.kind.label))
            put("Stage", select(stage?.label ?: "Not started"))
            put("Level", number(item.level?.toDouble()))
            put("JLPT", select(item.jlpt?.let { "N$it" }))
            put("Source", select(if (item.source.code == "llm") "AI-generated" else item.source.code))
        }

        internal fun statsProperties(d: NotionDayStats): JsonObject = buildJsonObject {
            put("Name", title(d.date))
            put(ID_PROPERTY, text("day:${d.date}"))
            putJsonObject("Date") { putJsonObject("date") { put("start", d.date) } }
            put("Reviews", number(d.reviews.toDouble()))
            put("Accuracy", number(d.accuracy))
            put("Streak", number(d.streak.toDouble()))
            put("Minutes", number(d.minutes.toDouble()))
        }

        private fun richText(s: String): JsonArray = buildJsonArray {
            // Notion limits one text object to 2000 characters.
            add(buildJsonObject { putJsonObject("text") { put("content", s.take(2000)) } })
        }

        private fun title(s: String): JsonObject = buildJsonObject { put("title", richText(s)) }
        private fun text(s: String): JsonObject = buildJsonObject { put("rich_text", richText(s)) }
        private fun select(s: String?): JsonObject = buildJsonObject {
            if (s == null) put("select", kotlinx.serialization.json.JsonNull) else putJsonObject("select") { put("name", s.replace(",", " ")) }
        }
        private fun number(v: Double?): JsonObject = buildJsonObject { put("number", v?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull) }
    }
}

