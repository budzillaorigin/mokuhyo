package app.tsumugi.integrations.ankiconnect

import app.tsumugi.net.NetTimeouts
import app.tsumugi.net.tsumugiHttpClient
import app.tsumugi.platform.Secrets
import app.tsumugi.settings.DeviceSettings
import app.tsumugi.srs.StudyItem
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class AnkiConnectException(message: String) : Exception(message)

/**
 * AnkiConnect's JSON API on the learner's desktop Anki (BRIEF_V2 G-09, DECISIONS D-119): `POST http://host:8765`
 * with `{"action", "version": 6, "params", "key"?}`. LAN-only and user-configured, so it has its own short
 * timeouts ([TIMEOUTS]: connect 3 s — a desktop that doesn't answer is asleep — and 15 s per request).
 */
class AnkiConnectClient(
    engine: HttpClientEngine,
    baseUrl: String,
    /** AnkiConnect's optional `apiKey` (its own secret, rule 14). */
    private val apiKey: String? = null,
) {
    private val http = tsumugiHttpClient(engine, TIMEOUTS)
    val url: String = normalize(baseUrl)

    /** AnkiConnect's API version (6 today); throws when nothing answers. */
    @Throws(Exception::class)
    suspend fun version(): Int = invoke("version", null).jsonPrimitive.intOrNull ?: throw AnkiConnectException("AnkiConnect returned no version")

    @Throws(Exception::class)
    suspend fun deckNames(): List<String> = invoke("deckNames", null).jsonArray.map { it.jsonPrimitive.content }

    @Throws(Exception::class)
    suspend fun modelNames(): List<String> = invoke("modelNames", null).jsonArray.map { it.jsonPrimitive.content }

    @Throws(Exception::class)
    suspend fun modelFieldNames(model: String): List<String> =
        invoke("modelFieldNames", buildJsonObject { put("modelName", model) }).jsonArray.map { it.jsonPrimitive.content }

    @Throws(Exception::class)
    suspend fun createDeck(name: String): Long? = invoke("createDeck", buildJsonObject { put("deck", name) }).jsonPrimitive.longOrNull

    /** Adds notes; each result is the new note id, or null when Anki refused it (usually a duplicate). */
    @Throws(Exception::class)
    suspend fun addNotes(notes: List<AnkiNote>): List<Long?> {
        val params = buildJsonObject { put("notes", JsonArray(notes.map { it.toJson() })) }
        val out = invoke("addNotes", params)
        return (out as? JsonArray)?.map { (it as? JsonPrimitive)?.longOrNull } ?: notes.map { null }
    }

    private suspend fun invoke(action: String, params: JsonObject?): JsonElement {
        val body = buildJsonObject {
            put("action", action)
            put("version", API_VERSION)
            if (params != null) put("params", params)
            apiKey?.takeIf { it.isNotBlank() }?.let { put("key", it) }
        }
        val response = try {
            http.post(url) { setBody(TextContent(body.toString(), ContentType.Application.Json)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AnkiConnectException("Can't reach Anki at $url. Is Anki open with AnkiConnect, and listening on the network (webBindAddress 0.0.0.0)? (${e.message})")
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) throw AnkiConnectException("AnkiConnect returned ${response.status.value}")
        val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw AnkiConnectException("Not an AnkiConnect reply")
        val error = o["error"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        // addNotes reports per-note failures in "error" while still returning results for the rest.
        if (error != null && (action != "addNotes" || o["result"] == null || o["result"] is JsonNull)) throw AnkiConnectException("Anki: $error")
        return o["result"] ?: JsonNull
    }

    companion object {
        const val API_VERSION = 6
        const val DEFAULT_PORT = 8765
        val TIMEOUTS = NetTimeouts(connectMs = 3_000, requestMs = 15_000, socketMs = null)

        /** "<lan-ip>" → "http://<lan-ip>:8765"; keeps an explicit scheme and port. */
        fun normalize(url: String): String {
            var u = url.trim().trimEnd('/')
            if (!u.contains("://")) u = "http://$u"
            val authority = u.substringAfter("://").substringBefore('/')
            if (!authority.contains(':')) u = u.replaceFirst(authority, "$authority:$DEFAULT_PORT")
            return u
        }
    }
}

/** A note for AnkiConnect's addNotes. */
data class AnkiNote(
    val deck: String,
    val model: String,
    val fields: Map<String, String>,
    val tags: List<String>,
    /** Base64 audio attached to [audioFields] (AnkiConnect stores it in the collection's media folder). */
    val audio: AnkiAudio? = null,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("deckName", deck)
        put("modelName", model)
        putJsonObject("fields") { fields.forEach { (k, v) -> put(k, v) } }
        putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
        putJsonObject("options") {
            put("allowDuplicate", false)
            put("duplicateScope", "deck")
        }
        audio?.let { a ->
            put("audio", buildJsonArray {
                add(buildJsonObject {
                    put("data", a.base64)
                    put("filename", a.filename)
                    put("fields", JsonArray(a.fields.map(::JsonPrimitive)))
                })
            })
        }
    }
}

data class AnkiAudio(val base64: String, val filename: String, val fields: List<String>)

/** Where and how to push: device-local (a LAN address, rule 16). */
data class AnkiConnectConfig(
    val url: String,
    val deck: String = DEFAULT_DECK,
    val model: String = "Basic",
    val frontField: String = "Front",
    val backField: String = "Back",
) {
    companion object {
        const val DEFAULT_DECK = "Tsumugi::Mined"
    }
}

data class AnkiPushResult(val added: Int, val duplicates: Int, val failed: List<String>)

/**
 * Pushes mined cards (reader sentence mining, media clips, personal cards) to desktop Anki through AnkiConnect
 * (BRIEF_V2 G-09). Configuration is per device ([DeviceSettings] keys below); the optional AnkiConnect key is in
 * the keychain. Notes carry a `tsumugi` tag and the item id as a tag, and Anki's duplicate check makes a second
 * push of the same words harmless.
 */
class AnkiConnectPush(
    private val deviceSettings: DeviceSettings,
    private val secrets: Secrets,
    private val engine: () -> HttpClientEngine,
    /** Base64 audio for an item (e.g. a clip's cut audio), or null. */
    private val audioFor: suspend (StudyItem) -> Pair<String, String>? = { null },
) {
    @Throws(Exception::class)
    suspend fun config(): AnkiConnectConfig? {
        val url = deviceSettings.get(URL_KEY) ?: return null
        return AnkiConnectConfig(
            url,
            deviceSettings.get(DECK_KEY) ?: AnkiConnectConfig.DEFAULT_DECK,
            deviceSettings.get(MODEL_KEY) ?: "Basic",
            deviceSettings.get(FRONT_KEY) ?: "Front",
            deviceSettings.get(BACK_KEY) ?: "Back",
        )
    }

    /** Saves the configuration after checking Anki answers and the note type has both fields. */
    @Throws(Exception::class)
    suspend fun configure(config: AnkiConnectConfig, apiKey: String? = null) {
        val client = AnkiConnectClient(engine(), config.url, apiKey)
        client.version()
        val fields = client.modelFieldNames(config.model)
        listOf(config.frontField, config.backField).filter { it !in fields }.takeIf { it.isNotEmpty() }?.let {
            throw AnkiConnectException("Note type \"${config.model}\" has no field ${it.joinToString { f -> "\"$f\"" }} (fields: ${fields.joinToString()})")
        }
        deviceSettings.put(URL_KEY, client.url)
        deviceSettings.put(DECK_KEY, config.deck)
        deviceSettings.put(MODEL_KEY, config.model)
        deviceSettings.put(FRONT_KEY, config.frontField)
        deviceSettings.put(BACK_KEY, config.backField)
        if (apiKey.isNullOrBlank()) secrets.remove(SECRET_KEY) else secrets.put(SECRET_KEY, apiKey)
    }

    @Throws(Exception::class)
    suspend fun push(items: List<StudyItem>, onProgress: (Int, Int) -> Unit = { _, _ -> }): AnkiPushResult {
        val config = config() ?: throw AnkiConnectException("Set up AnkiConnect first")
        val client = AnkiConnectClient(engine(), config.url, secrets.get(SECRET_KEY))
        client.createDeck(config.deck)
        var added = 0
        var duplicates = 0
        val failed = ArrayList<String>()
        items.chunked(BATCH).forEachIndexed { i, batch ->
            val notes = batch.map { note(it, config, audioFor(it)) }
            try {
                client.addNotes(notes).forEach { if (it != null) added++ else duplicates++ }
            } catch (e: AnkiConnectException) {
                failed += e.message.orEmpty()
            }
            onProgress(minOf((i + 1) * BATCH, items.size), items.size)
        }
        return AnkiPushResult(added, duplicates, failed)
    }

    companion object {
        const val URL_KEY = "ankiconnect.url"
        const val DECK_KEY = "ankiconnect.deck"
        const val MODEL_KEY = "ankiconnect.model"
        const val FRONT_KEY = "ankiconnect.front"
        const val BACK_KEY = "ankiconnect.back"
        const val SECRET_KEY = "ankiconnect.key"
        private const val BATCH = 50

        /** Front: the word (and its mined sentence); back: reading, meanings, and the item's AI badge if any. */
        internal fun note(item: StudyItem, config: AnkiConnectConfig, audio: Pair<String, String>?): AnkiNote {
            val sentence = sentenceOf(item.context)
            val front = buildString {
                append(html(item.primaryText))
                if (sentence != null && sentence != item.primaryText) append("<br><small>").append(html(sentence)).append("</small>")
            }
            val back = buildString {
                item.reading?.takeIf { it != item.primaryText }?.let { append(html(it)).append("<br>") }
                append(html(item.meanings.joinToString("; ")))
                if (item.source.code == "llm") append("<br><small>AI-generated</small>")
            }
            val tags = listOf("tsumugi", "tsumugi::" + item.kind.name.lowercase(), "tsumugi-id::" + item.id.replace(' ', '_'))
            return AnkiNote(
                config.deck, config.model, mapOf(config.frontField to front, config.backField to back), tags,
                audio?.let { (b64, name) -> AnkiAudio(b64, name, listOf(config.frontField)) },
            )
        }

        /** The mined sentence stored in an item's context (`{"sentence": …}` from mining), when there is one. */
        private fun sentenceOf(context: String?): String? = context?.let {
            runCatching { Json.parseToJsonElement(it).jsonObject["sentence"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                ?: it.takeIf { c -> !c.trimStart().startsWith("{") }
        }

        private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
