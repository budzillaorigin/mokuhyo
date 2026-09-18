package app.tsumugi.sync

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.speaking.ConversationMode
import app.tsumugi.speaking.ConversationService
import app.tsumugi.speaking.ConversationTurn
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.StatsService
import app.tsumugi.study.TodayBlockKind
import app.tsumugi.study.TodayPlanner
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** BRIEF_V2 G-02/G-11 sync and leaderboard: union-synced conversations, finished blocks and freezes; the opt-in client. */
class Phase10SyncTest {

    private val clock = TestClock()
    private val server = FakeSyncServer()

    private inner class Device(val id: String) {
        val driver = inMemoryDriver(TsumugiDatabase.Schema)
        val db = TsumugiDatabase(driver)
        val srs = SrsRepository(db, id, clock)
        val settings = SettingsRepository(db, clock)
        val engine = SyncEngine(driver, db, srs, id, server, null, clock)
        val conversations = ConversationService(db, id, clock)
        val planner = TodayPlanner(db, settings, clock) { TimeZone.UTC }
        val stats = StatsService(db, srs, settings, clock) { TimeZone.UTC }
    }

    @Test
    fun conversationsBlocksAndFreezesMergeByUnion() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        val turns = listOf(ConversationTurn(ConversationTurn.PARTNER, "こんにちは。"), ConversationTurn(ConversationTurn.LEARNER, "こんにちは。"))
        a.conversations.save(ConversationMode.FREE_TALK, null, "N4", turns, 0, "fake")
        b.conversations.save(ConversationMode.SCENARIO, "konbini", "N5", turns, 0, null)
        a.planner.markDone(TodayBlockKind.SHADOWING)
        b.planner.markDone(TodayBlockKind.SHADOWING)
        b.planner.markDone(TodayBlockKind.WRITING)
        a.stats.freeze()

        a.engine.sync(); b.engine.sync(); a.engine.sync()

        for (d in listOf(a, b)) {
            assertEquals(2, d.conversations.recent().size, d.id)
            assertEquals(setOf("SHADOWING", "WRITING"), d.db.studyQueries.blocksDoneOn("2026-09-01").executeAsList().toSet(), d.id)
            assertEquals(1, d.stats.freezeDays().size, d.id)
        }
        assertEquals(
            a.conversations.recent().map { it.id to it.turns }.toSet(),
            b.conversations.recent().map { it.id to it.turns }.toSet(),
        )
    }

    private class MemoryTokens(override var accessToken: String? = "access", override var refreshToken: String? = "refresh") : TokenStore

    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun leaderboardIsOffByDefaultAndOptInPatchesTheAccount() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val settings = SettingsRepository(db, clock)
        val requests = ArrayList<String>()
        var optedIn = false
        val engine = MockEngine { req ->
            val body = (req.body as? TextContent)?.text.orEmpty()
            requests += "${req.method.value} ${req.url.encodedPathAndQuery} $body".trim()
            when (req.url.encodedPath) {
                "/v1/account" -> {
                    if (body.contains("\"leaderboardOptIn\":true")) optedIn = true
                    respond("""{"userId":"u","email":"e@x","displayName":"Budi","leaderboardOptIn":$optedIn}""", HttpStatusCode.OK, json)
                }
                "/v1/leaderboard" -> respond("""[{"displayName":"Budi","reviews":120,"streak":9}]""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpSyncClient(engine, "https://sync.example", MemoryTokens())

        assertIs<LeaderboardState.NotSignedIn>(LeaderboardService({ null }, { false }, settings).load())
        val service = LeaderboardService({ client }, { false }, settings)
        assertIs<LeaderboardState.OptedOut>(service.load())
        assertTrue(requests.isEmpty(), "nothing is sent before opting in")

        service.setOptIn(true, "Budi")
        assertTrue(service.isOptedIn())
        assertTrue(requests.single().startsWith("PATCH /v1/account"))
        val rows = assertIs<LeaderboardState.Rows>(service.load(LeaderboardPeriod.MONTH))
        assertEquals(120, rows.rows.single().reviews)
        assertTrue(requests.any { it.startsWith("GET /v1/leaderboard?period=month") })

        assertIs<LeaderboardState.Encrypted>(LeaderboardService({ client }, { true }, settings).load())
    }
}
