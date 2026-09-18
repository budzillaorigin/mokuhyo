package app.tsumugi.integrations.wanikani

import app.tsumugi.testing.TestClock
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WaniKaniClientTest {

    private val clock = TestClock()
    private val server = FakeWaniKani()
    private val sleeps = ArrayList<Duration>()

    private fun client(requestsPerMinute: Int = 60, onCache: (Map<String, WkCachedResponse>) -> Unit = {}) =
        WaniKaniClient(
            token = "secret-token", engine = server.engine, clock = clock,
            sleep = { sleeps += it; clock.advance(it) }, onCacheUpdated = onCache, requestsPerMinute = requestsPerMinute,
        )

    @Test
    fun sendsAuthAndRevisionHeaders() = runTest {
        server.json("/user", WkFixtures.user)
        val user = client().user()
        assertEquals("tester", user.username)
        assertEquals(7, user.level)
        val headers = server.requests.single().headers
        assertEquals("Bearer secret-token", headers[HttpHeaders.Authorization])
        assertEquals("20170710", headers["Wanikani-Revision"])
    }

    @Test
    fun followsPaginationAndPassesCursor() = runTest {
        server.routes["GET /assignments"] = { req ->
            if (req.url.parameters["page_after_id"] == null) {
                ok(WkFixtures.collection(WkFixtures.assignmentsPage1, "https://api.wanikani.com/v2/assignments?page_after_id=3&started=true"))
            } else {
                ok(WkFixtures.collection(WkFixtures.assignmentsPage2, updatedAt = "2026-08-30T00:00:00.000000Z"))
            }
        }
        val page = client().assignments(updatedAfter = "2026-08-01T00:00:00.000000Z")
        assertEquals(listOf(1L, 2, 3, 4, 5, 6), page.items.map { it.id })
        assertEquals("2026-08-31T12:00:00.000000Z", page.dataUpdatedAt, "cursor comes from the first page")
        val first = server.requests.first().url.parameters
        assertEquals("2026-08-01T00:00:00.000000Z", first["updated_after"])
        assertEquals("true", first["started"])
    }

    @Test
    fun waitsOn429UntilReset() = runTest {
        var calls = 0
        server.routes["GET /user"] = {
            if (calls++ == 0) {
                respond("", HttpStatusCode.TooManyRequests, headersOf("RateLimit-Reset", (clock.now().epochSeconds + 5).toString()))
            } else {
                ok(WkFixtures.user)
            }
        }
        assertEquals("tester", client().user().username)
        assertEquals(listOf(5.seconds), sleeps)
    }

    @Test
    fun revalidatesWithEtagAndUses304Body() = runTest {
        var saved: Map<String, WkCachedResponse> = emptyMap()
        var calls = 0
        server.routes["GET /user"] = { req ->
            if (calls++ == 0) {
                ok(WkFixtures.user, HttpHeaders.ETag to "W/\"abc\"", HttpHeaders.LastModified to "Mon, 31 Aug 2026 12:00:00 GMT")
            } else {
                assertEquals("W/\"abc\"", req.headers[HttpHeaders.IfNoneMatch])
                assertEquals("Mon, 31 Aug 2026 12:00:00 GMT", req.headers[HttpHeaders.IfModifiedSince])
                respond("", HttpStatusCode.NotModified)
            }
        }
        val c = client { saved = it }
        c.user()
        assertEquals("tester", c.user().username, "304 served from cache")
        assertTrue(saved.values.single().body.contains("tester"))

        // A new client seeded with the persisted cache revalidates immediately.
        val restored = WaniKaniClient("secret-token", server.engine, clock, { clock.advance(it) }, initialCache = saved)
        assertEquals("tester", restored.user().username)
    }

    @Test
    fun rateLimitsToRequestsPerMinute() = runTest {
        server.json("/user", WkFixtures.user)
        val c = client(requestsPerMinute = 2)
        repeat(3) { c.user() }
        assertEquals(1, sleeps.size)
        assertEquals(60.seconds, sleeps.single())
    }

    @Test
    fun unauthorizedThrows() = runTest {
        server.routes["GET /user"] = { respond("{}", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<WaniKaniException> { client().user() }
        assertEquals(401, e.status)
    }
}
