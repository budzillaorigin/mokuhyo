package app.tsumugi.integrations

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.Stage
import app.tsumugi.integrations.ankiconnect.AnkiConnectClient
import app.tsumugi.integrations.ankiconnect.AnkiConnectConfig
import app.tsumugi.integrations.ankiconnect.AnkiConnectException
import app.tsumugi.integrations.ankiconnect.AnkiConnectPush
import app.tsumugi.integrations.notion.NotionClient
import app.tsumugi.integrations.notion.NotionDayStats
import app.tsumugi.integrations.notion.NotionException
import app.tsumugi.integrations.notion.NotionExport
import app.tsumugi.integrations.wanikani.FakeSecrets
import app.tsumugi.settings.DeviceSettings
import app.tsumugi.srs.StudyItem
import app.tsumugi.testing.inMemoryDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration

private fun HttpRequestData.json(): JsonObject =
    Json.parseToJsonElement((body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

class IntegrationsPushTest {
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val secrets = FakeSecrets()

    private val neko = StudyItem(
        id = "jmdict:1467640", kind = ItemKind.VOCAB, primaryText = "猫", reading = "ねこ", meanings = listOf("cat"),
        acceptedReadings = listOf("ねこ"), source = ItemSource.USER, level = null, jlpt = 5, context = "猫が好きです。",
        synonyms = emptyList(), myStory = "",
    )

    // --- Notion ---------------------------------------------------------------------------------------------

    private val itemsDb = "0123456789abcdef0123456789abcdef"
    private val statsDb = "fedcba9876543210fedcba9876543210"

    private fun notionEngine(requests: MutableList<HttpRequestData>, existing: Set<String> = emptySet(), throttleOnce: Boolean = false): MockEngine {
        var throttled = !throttleOnce
        return MockEngine { req ->
            requests += req
            assertEquals("api.notion.com", req.url.host, "the Notion token goes only to api.notion.com")
            assertEquals("Bearer secret_abc", req.headers[HttpHeaders.Authorization])
            assertEquals(NotionClient.API_VERSION, req.headers[NotionClient.VERSION_HEADER])
            val path = req.url.encodedPath
            when {
                !throttled -> {
                    throttled = true
                    respond("""{"message":"slow down"}""", HttpStatusCode.TooManyRequests, headersOf("Retry-After", "1"))
                }
                req.method.value == "GET" && path.startsWith("/v1/databases/") -> {
                    val props = if (path.endsWith(itemsDb)) NotionExport.ITEM_PROPERTIES else NotionExport.STATS_PROPERTIES
                    val body = props.entries.joinToString(",") { (k, t) -> "\"$k\":{\"type\":\"$t\"}" }
                    respond("""{"title":[{"plain_text":"Tsumugi"}],"properties":{$body}}""", HttpStatusCode.OK, jsonHeaders)
                }
                path.endsWith("/query") -> {
                    val wanted = req.json()["filter"]!!.jsonObject["rich_text"]!!.jsonObject["equals"]!!.jsonPrimitive.content
                    val results = if (wanted in existing) """[{"id":"page-1"}]""" else "[]"
                    respond("""{"results":$results}""", HttpStatusCode.OK, jsonHeaders)
                }
                path == "/v1/pages" -> respond("""{"id":"new-page"}""", HttpStatusCode.OK, jsonHeaders)
                path.startsWith("/v1/pages/") -> respond("""{"id":"x"}""", HttpStatusCode.OK, jsonHeaders)
                else -> respond("{}", HttpStatusCode.NotFound, jsonHeaders)
            }
        }
    }

    private fun notion(engine: MockEngine, sleeps: MutableList<Duration> = mutableListOf()) =
        NotionExport(db, secrets, { token -> NotionClient(token, engine, sleep = { sleeps += it }) })

    @Test
    fun notionConnectChecksTheMappingAndKeepsTheTokenInTheKeychain() = runTest {
        val requests = ArrayList<HttpRequestData>()
        val export = notion(notionEngine(requests))
        val problems = export.connect("secret_abc", "https://www.notion.so/me/Words-$itemsDb?v=1", statsDb)
        assertTrue(problems.isEmpty(), problems.toString())
        assertEquals("secret_abc", secrets.get(NotionExport.TOKEN_KEY))
        assertEquals(itemsDb, export.config()!!.itemsDatabaseId)
        assertTrue(db.userQueries.integration("NOTION").executeAsOne().config.contains(statsDb))
        assertTrue(!db.userQueries.integration("NOTION").executeAsOne().config.contains("secret"), "no token in the database")
    }

    @Test
    fun notionPushCreatesThenUpdatesByTsumugiId() = runTest {
        val requests = ArrayList<HttpRequestData>()
        val sleeps = ArrayList<Duration>()
        val export = notion(notionEngine(requests, existing = setOf("day:2026-09-17"), throttleOnce = true), sleeps)
        secrets.put(NotionExport.TOKEN_KEY, "secret_abc")
        db.userQueries.putIntegration("NOTION", """{"itemsDatabaseId":"$itemsDb","statsDatabaseId":"$statsDb"}""", null, null)

        val items = export.pushItems(listOf(neko), mapOf(neko.id to Stage.GURU))
        assertEquals(1, items.created)
        val create = requests.last { it.url.encodedPath == "/v1/pages" }.json()
        val props = create["properties"]!!.jsonObject
        assertEquals("猫", props["Name"]!!.jsonObject["title"]!!.jsonArray[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("Guru", props["Stage"]!!.jsonObject["select"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(itemsDb, create["parent"]!!.jsonObject["database_id"]!!.jsonPrimitive.content)
        assertTrue(sleeps.any { it.inWholeMilliseconds >= 1000 }, "a 429 waits for Retry-After")

        val stats = export.pushStats(listOf(NotionDayStats("2026-09-17", 40, 0.9, 3, 12), NotionDayStats("2026-09-18", 10, null, 4, 3)))
        assertEquals(1, stats.updated)
        assertEquals(1, stats.created)
        assertTrue(requests.any { it.method.value == "PATCH" && it.url.encodedPath == "/v1/pages/page-1" })
        assertTrue(db.userQueries.integration("NOTION").executeAsOne().last_sync_at != null)
    }

    @Test
    fun notionWithoutATokenOrWithABadDatabaseStops() = runTest {
        val export = notion(notionEngine(ArrayList()))
        assertFailsWith<NotionException> { export.pushStats(emptyList()) }
        val engine = MockEngine { respond("""{"message":"Could not find database"}""", HttpStatusCode.NotFound, jsonHeaders) }
        val bad = NotionExport(db, secrets, { NotionClient(it, engine, sleep = {}) })
        assertFailsWith<NotionException> { bad.connect("secret_abc", itemsDb, null) }
        assertEquals(null, secrets.get(NotionExport.TOKEN_KEY))
        assertEquals("0123456789abcdef0123456789abcdef", NotionClient.clean("01234567-89ab-cdef-0123-456789abcdef"))
    }

    // --- AnkiConnect ----------------------------------------------------------------------------------------

    private fun ankiEngine(requests: MutableList<JsonObject>): MockEngine = MockEngine { req ->
        val body = req.json()
        requests += body
        assertEquals("<lan-ip>", req.url.host)
        assertEquals(8765, req.url.port)
        val result = when (body["action"]!!.jsonPrimitive.content) {
            "version" -> "6"
            "modelFieldNames" -> """["Front","Back"]"""
            "createDeck" -> "1"
            "addNotes" -> "[1001, null]"
            else -> "null"
        }
        respond("""{"result":$result,"error":null}""", HttpStatusCode.OK, jsonHeaders)
    }

    @Test
    fun ankiConnectPushesNotesAndCountsDuplicates() = runTest {
        val requests = ArrayList<JsonObject>()
        val engine = ankiEngine(requests)
        val device = DeviceSettings(db)
        val push = AnkiConnectPush(device, secrets, { engine })
        push.configure(AnkiConnectConfig("<lan-ip>"), apiKey = "k1")
        assertEquals("http://<lan-ip>:8765", device.get(AnkiConnectPush.URL_KEY), "a LAN address is device-local")
        assertEquals("k1", secrets.get(AnkiConnectPush.SECRET_KEY))

        val clip = neko.copy(id = "clip:1", kind = ItemKind.LISTENING, primaryText = "雨が降るかもしれません。", context = """{"type":"clip"}""")
        val result = push.push(listOf(neko, clip))
        assertEquals(1, result.added)
        assertEquals(1, result.duplicates)
        val add = requests.last { it["action"]!!.jsonPrimitive.content == "addNotes" }
        assertEquals("k1", add["key"]!!.jsonPrimitive.content)
        assertEquals(6, add["version"]!!.jsonPrimitive.content.toInt())
        val note = add["params"]!!.jsonObject["notes"]!!.jsonArray[0].jsonObject
        assertEquals("Tsumugi::Mined", note["deckName"]!!.jsonPrimitive.content)
        val front = note["fields"]!!.jsonObject["Front"]!!.jsonPrimitive.content
        assertTrue(front.startsWith("猫") && front.contains("猫が好きです。"), front)
        assertTrue(note["tags"]!!.jsonArray.any { it.jsonPrimitive.content == "tsumugi-id::jmdict:1467640" })
    }

    @Test
    fun ankiConnectReportsAnUnreachableDesktopAndABadNoteType() = runTest {
        val down = MockEngine { throw IllegalStateException("connection refused") }
        val e = assertFailsWith<AnkiConnectException> { AnkiConnectClient(down, "<lan-ip>").version() }
        assertTrue(e.message!!.contains("Can't reach Anki"))

        val engine = ankiEngine(ArrayList())
        val push = AnkiConnectPush(DeviceSettings(db), secrets, { engine })
        assertFailsWith<AnkiConnectException> { push.configure(AnkiConnectConfig("<lan-ip>", frontField = "Expression")) }
        assertEquals(null, push.config())
        assertEquals("http://anki.local:9000", AnkiConnectClient.normalize("anki.local:9000/"))
        assertEquals(3_000L, AnkiConnectClient.TIMEOUTS.connectMs)
    }
}
