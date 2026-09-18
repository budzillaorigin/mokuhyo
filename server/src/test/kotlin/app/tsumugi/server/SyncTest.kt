package app.tsumugi.server

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncTest {

    private suspend fun io.ktor.client.HttpClient.pull(token: String, since: Long = 0, limit: Int = 1000): PullResponse =
        getAuth("/v1/sync/pull?since=$since&limit=$limit", token).body()

    @Test
    fun pushThenPullInOrderAndRepushIsIdempotent() = serverTest { client ->
        val t = client.signUp("sync@example.com")
        val changes = (1..3).map { reviewChange("r$it", 1_000L + it, t.deviceId) }
        val first: PushResponse = client.postJson("/v1/sync/push", PushRequest(changes), t.accessToken).body()
        assertEquals(PushResponse(3, 3), first)

        // A retry after a timeout sends the same changes again: nothing new is stored.
        val retry: PushResponse = client.postJson("/v1/sync/push", PushRequest(changes), t.accessToken).body()
        assertEquals(PushResponse(3, 3), retry)

        val pulled = client.pull(t.accessToken)
        assertEquals(listOf(1L, 2L, 3L), pulled.changes.map { it.seq })
        assertEquals(listOf("r1", "r2", "r3"), pulled.changes.map { it.key })
        assertEquals(changes.first().row, pulled.changes.first().row, "payload returned verbatim")
        assertEquals(3, pulled.lastSeq)
        assertEquals(false, pulled.hasMore)
        assertEquals(PullResponse(emptyList(), 3, false), client.pull(t.accessToken, since = 3))
    }

    @Test
    fun twoDevicesInterleaveAndPullPages() = serverTest { client ->
        val phone = client.signUp("two@example.com", device = "phone")
        val tablet = client.login("two@example.com", "tablet")
        repeat(3) { round ->
            client.postJson("/v1/sync/push", PushRequest(listOf(reviewChange("p$round", 10L + round, phone.deviceId))), phone.accessToken)
            client.postJson("/v1/sync/push", PushRequest(listOf(reviewChange("t$round", 20L + round, tablet.deviceId))), tablet.accessToken)
        }
        val all = client.pull(tablet.accessToken)
        assertEquals((1L..6L).toList(), all.changes.map { it.seq })
        assertEquals(listOf("p0", "t0", "p1", "t1", "p2", "t2"), all.changes.map { it.key })
        assertEquals(listOf(phone.deviceId, tablet.deviceId), all.changes.take(2).map { it.deviceId })

        val page1 = client.pull(phone.accessToken, limit = 4)
        assertEquals(4, page1.changes.size)
        assertTrue(page1.hasMore)
        val page2 = client.pull(phone.accessToken, since = page1.lastSeq, limit = 4)
        assertEquals(listOf("p2", "t2"), page2.changes.map { it.key })
        assertEquals(false, page2.hasMore)

        // Another user's log is separate and starts at seq 1.
        val other = client.signUp("other@example.com")
        client.postJson("/v1/sync/push", PushRequest(listOf(reviewChange("x", 1, other.deviceId))), other.accessToken)
        assertEquals(listOf(1L), client.pull(other.accessToken).changes.map { it.seq })
    }

    @Test
    fun tombstonesPassThrough() = serverTest { client ->
        val t = client.signUp("tomb@example.com")
        val upsert = Change("word_list", "list-1", "UPSERT", JsonObject(mapOf("name" to JsonPrimitive("Food"))), null, 5, t.deviceId)
        val delete = Change("word_list", "list-1", "DELETE", null, null, 6, t.deviceId)
        client.postJson("/v1/sync/push", PushRequest(listOf(upsert, delete)), t.accessToken)
        val pulled = client.pull(t.accessToken).changes
        assertEquals(listOf("UPSERT", "DELETE"), pulled.map { it.op })
        assertNull(pulled.last().row)
    }

    @Test
    fun invalidChangesAreRejected() = serverTest { client ->
        val t = client.signUp("bad@example.com")
        val both = Change("item", "k", "UPSERT", JsonObject(emptyMap()), "c2VhbGVk", 1, t.deviceId)
        assertEquals(400, client.postJson("/v1/sync/push", PushRequest(listOf(both)), t.accessToken).status.value)
        val badOp = Change("item", "k", "MERGE", JsonObject(emptyMap()), null, 1, t.deviceId)
        assertEquals(400, client.postJson("/v1/sync/push", PushRequest(listOf(badOp)), t.accessToken).status.value)
        assertEquals(400, client.postJson("/v1/sync/push", mapOf("nope" to 1), t.accessToken).status.value)
    }

    @Test
    fun tooManyChangesPerPush() = serverTest(testConfig().copy(maxPushChanges = 2)) { client ->
        val t = client.signUp("many@example.com")
        val changes = (1..3).map { reviewChange("r$it", it.toLong(), t.deviceId) }
        assertEquals(413, client.postJson("/v1/sync/push", PushRequest(changes), t.accessToken).status.value)
    }

    @Test
    fun endToEndSealedPayloadsAreOpaque() = serverTest { client ->
        val t = client.signUp("e2e@example.com")
        assertEquals(400, client.patchJson("/v1/account", AccountPatch(e2eEnabled = true), t.accessToken).status.value, "salt first")
        val account: Account = client.patchJson("/v1/account", AccountPatch(e2eSalt = "0123456789abcdef0123", e2eEnabled = true), t.accessToken).body()
        assertEquals(true, account.e2eEnabled)
        assertEquals("0123456789abcdef0123", account.e2eSalt)

        val sealed = Change("review", "r1", "UPSERT", null, "bm9uY2UgY2lwaGVydGV4dA", 99, t.deviceId)
        client.postJson("/v1/sync/push", PushRequest(listOf(sealed)), t.accessToken)
        val pulled = client.pull(t.accessToken).changes.single()
        assertEquals("bm9uY2UgY2lwaGVydGV4dA", pulled.sealed)
        assertNull(pulled.row)
    }

    @Test
    fun leaderboardIsOptInAndExcludesEncryptedAccounts() = serverTest { client ->
        val now = System.currentTimeMillis()
        suspend fun user(email: String, reviews: Int, optIn: Boolean, e2e: Boolean) {
            val t = client.signUp(email)
            if (e2e) client.patchJson("/v1/account", AccountPatch(e2eSalt = "salt-salt-salt-salt"), t.accessToken)
            client.patchJson("/v1/account", AccountPatch(leaderboardOptIn = optIn, e2eEnabled = e2e.takeIf { it }), t.accessToken)
            val changes = (1..reviews).map { reviewChange("$email-$it", now - it * 1000L, t.deviceId) }
            client.postJson("/v1/sync/push", PushRequest(changes), t.accessToken)
        }
        user("hana@example.com", 5, optIn = true, e2e = false)
        user("ken@example.com", 9, optIn = true, e2e = false)
        user("secret@example.com", 50, optIn = true, e2e = true)
        user("private@example.com", 40, optIn = false, e2e = false)

        val t = client.login("hana@example.com", "phone")
        val board: List<LeaderboardEntry> = client.getAuth("/v1/leaderboard?period=week", t.accessToken).body()
        assertEquals(listOf("ken" to 9, "hana" to 5), board.map { it.displayName to it.reviews })
        assertEquals(1, board.first().streak)
        assertEquals(400, client.getAuth("/v1/leaderboard?period=year", t.accessToken).status.value)
    }

    @Test
    fun streakCountsConsecutiveDays() {
        val today = LocalDate.of(2026, 9, 18)
        val days = setOf(today, today.minusDays(1), today.minusDays(2), today.minusDays(4))
        assertEquals(3, SyncService.streak(days, today))
        assertEquals(2, SyncService.streak(setOf(today.minusDays(1), today.minusDays(2)), today), "today not studied yet")
        assertEquals(0, SyncService.streak(setOf(today.minusDays(3)), today))
    }

    @Test
    fun blobsRespectTheQuota() = serverTest(testConfig().copy(blobQuotaBytes = 100, maxBlobBytes = 80)) { client ->
        val t = client.signUp("blob@example.com")
        suspend fun putBlob(id: String, size: Int) = client.put("/v1/blobs/$id") {
            bearerAuth(t.accessToken)
            contentType(ContentType.Audio.MPEG)
            setBody(ByteArray(size) { it.toByte() })
        }.status.value
        assertEquals(204, putBlob("a.m4a", 60))
        assertEquals(413, putBlob("b.m4a", 60), "over quota")
        assertEquals(204, putBlob("a.m4a", 70), "replacing a blob only counts the new size")
        assertEquals(413, putBlob("c.m4a", 90), "over max blob size")
        assertEquals(400, putBlob("bad!id", 1))

        val response = client.get("/v1/blobs/a.m4a") { bearerAuth(t.accessToken) }
        assertContentEquals(ByteArray(70) { it.toByte() }, response.readRawBytes())
        assertEquals(204, client.deleteAuth("/v1/blobs/a.m4a", t.accessToken).status.value)
        assertEquals(404, client.getAuth("/v1/blobs/a.m4a", t.accessToken).status.value)
    }

    @Test
    fun oversizedBodiesAreRejected() = serverTest(testConfig().copy(maxBodyBytes = 1_000)) { client ->
        val t = client.signUp("big@example.com")
        val changes = (1..50).map { reviewChange("r$it", it.toLong(), t.deviceId) }
        assertEquals(413, client.postJson("/v1/sync/push", PushRequest(changes), t.accessToken).status.value)
        val chunked = client.put("/v1/blobs/x") {
            bearerAuth(t.accessToken)
            contentType(ContentType.Application.OctetStream)
            setBody(io.ktor.utils.io.ByteReadChannel(ByteArray(2_000)))
        }
        assertEquals(413, chunked.status.value, "bodies streamed without a length are cut off at the limit")
    }

    @Test
    fun rateLimitPerUser() = serverTest(testConfig().copy(rateLimitPerMinute = 5)) { client ->
        val t = client.signUp("limit@example.com")
        val codes = (1..10).map { client.getAuth("/v1/account", t.accessToken).status.value }
        assertTrue(429 in codes, "requests beyond the per-minute budget get 429: $codes")
        assertEquals(200, client.get("/v1/health").status.value, "health is never limited")
    }
}
