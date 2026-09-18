package app.tsumugi.sync

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** In-memory server with the protocol's push/pull semantics (per-user seq, idempotent pushes). */
class FakeSyncServer : SyncTransport {
    val changes = ArrayList<Change>()
    private val seen = HashSet<String>()

    override suspend fun push(changes: List<Change>): PushResponse {
        var accepted = 0
        for (c in changes) {
            val identity = listOf(c.table, c.key, c.updatedAt, c.deviceId, c.op).joinToString("|")
            if (!seen.add(identity)) continue
            this.changes += c.copy(seq = this.changes.size + 1L)
            accepted++
        }
        return PushResponse(accepted, this.changes.size.toLong())
    }

    override suspend fun pull(since: Long, limit: Int): PullResponse {
        val page = changes.filter { it.seq!! > since }.take(limit)
        val last = page.lastOrNull()?.seq ?: since
        return PullResponse(page, last, changes.any { it.seq!! > last })
    }
}

class SyncMergeTest {

    private val clock = TestClock()
    private val server = FakeSyncServer()

    private inner class Device(val id: String, sealer: Sealer? = null) {
        val driver: SqlDriver = inMemoryDriver(TsumugiDatabase.Schema)
        val db = TsumugiDatabase(driver)
        val srs = SrsRepository(db, id, clock)
        val settings = SettingsRepository(db, clock)
        val engine = SyncEngine(driver, db, srs, id, server, sealer, clock)

        suspend fun sync() = engine.sync()
        fun reviews() = db.srsQueries.allReviews().executeAsList().map { it.id to it.card_id }.toSet()
        suspend fun card(id: String) = srs.card(id)?.fsrs
    }

    private val water = NewItem(
        "k:水", ItemKind.KANJI, "水", "すい", listOf("water"), listOf("すい", "みず"), ItemSource.PACK,
        listOf(CardDirection.MEANING, CardDirection.READING), level = 1,
    )
    private val meaning = SrsRepository.cardId("k:水", CardDirection.MEANING)
    private val reading = SrsRepository.cardId("k:水", CardDirection.READING)

    @Test
    fun interleavedOfflineReviewsConvergeToIdenticalCards() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        a.srs.addItems(listOf(water))
        a.srs.introduce(listOf(meaning, reading))
        a.sync()
        b.sync()
        assertEquals("水", b.srs.item("k:水")!!.primaryText)
        assertEquals(a.card(meaning), b.card(meaning), "introduced card replays identically")

        // Both devices review offline, interleaved in time.
        clock.advance(10.minutes)
        a.srs.review(meaning, Rating.GOOD)
        clock.advance(30.seconds)
        b.srs.review(reading, Rating.AGAIN)
        clock.advance(30.seconds)
        b.srs.review(meaning, Rating.GOOD)
        clock.advance(1.minutes)
        a.srs.review(reading, Rating.GOOD)

        a.sync(); b.sync(); a.sync()

        assertEquals(a.reviews(), b.reviews())
        assertEquals(6, a.reviews().size, "2 introductions + 4 answers")
        for (card in listOf(meaning, reading)) assertEquals(a.card(card), b.card(card), card)
        assertEquals(0, a.engine.pendingChanges())
        assertEquals(0, b.engine.pendingChanges())
    }

    @Test
    fun notesAndSettingsAreLastWriterWinsWithDeviceTieBreak() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        a.srs.saveNote("k:水", myStory = "from A")
        clock.advance(1.seconds)
        b.srs.saveNote("k:水", myStory = "from B, later")
        a.sync(); b.sync(); a.sync()
        assertEquals("from B, later", a.srs.note("k:水").myStory)
        assertEquals("from B, later", b.srs.note("k:水").myStory)

        // Same timestamp on both: the higher device id wins everywhere.
        a.settings.put("today.budgetMinutes", "40")
        b.settings.put("today.budgetMinutes", "10")
        a.sync(); b.sync(); a.sync()
        assertEquals("10", a.settings.get("today.budgetMinutes"))
        assertEquals("10", b.settings.get("today.budgetMinutes"))

        // A newer local edit on A survives a stale remote copy.
        clock.advance(1.seconds)
        a.settings.put("today.budgetMinutes", "60")
        a.sync(); b.sync(); a.sync()
        assertEquals("60", b.settings.get("today.budgetMinutes"))
    }

    @Test
    fun tombstonesPropagate() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        a.db.userQueries.insertList("list-1", "Food", clock.now().toEpochMilliseconds(), clock.now().toEpochMilliseconds())
        a.db.userQueries.putListEntry("list-1", "jmdict:1", "猫", "ねこ", "cat", clock.now().toEpochMilliseconds())
        a.srs.addItems(listOf(water))
        a.sync(); b.sync()
        assertEquals(listOf("Food"), b.db.userQueries.lists().executeAsList().map { it.name })
        assertEquals(1, b.db.userQueries.listEntries("list-1").executeAsList().size)

        clock.advance(1.seconds)
        a.db.userQueries.deleteList(clock.now().toEpochMilliseconds(), "list-1")
        a.db.srsQueries.deleteItem(clock.now().toEpochMilliseconds(), "k:水")
        a.sync(); b.sync()
        assertTrue(b.db.userQueries.lists().executeAsList().isEmpty())
        assertNull(b.srs.items(listOf("k:水"))["k:水"], "deleted item is hidden")
        assertEquals(1L, b.db.srsQueries.itemById("k:水").executeAsOne().deleted)
    }

    @Test
    fun applyingRemoteChangesCreatesNoMarkers() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        a.srs.addItems(listOf(water))
        a.srs.introduce(listOf(meaning))
        a.srs.saveNote("k:水", myStory = "story")
        a.sync()
        b.sync()
        assertEquals(0, b.engine.pendingChanges(), "no echo")
        val before = server.changes.size
        b.sync()
        assertEquals(before, server.changes.size)
    }

    @Test
    fun rePushIsIdempotent() = runTest {
        val a = Device("device-a")
        a.srs.saveNote("k:水", myStory = "once")
        a.sync()
        val pushed = server.changes.toList()
        assertEquals(0, server.push(pushed.map { it.copy(seq = null) }).accepted)
        assertEquals(pushed.size, server.changes.size)
    }

    @Test
    fun suspendedFlagSyncs() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        a.srs.addItems(listOf(water))
        a.srs.introduce(listOf(meaning))
        a.sync(); b.sync()
        a.srs.setSuspended(meaning, true)
        a.sync(); b.sync()
        assertTrue(assertNotNull(b.srs.card(meaning)).suspended)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun endToEndEncryptedPayloadsAreOpaqueToTheServer() = runTest {
        val salt = E2eKeys.newSalt()
        val params = E2eKeys.Params(iterations = 1, memoryKiB = 64)
        val key = E2eKeys.derive("correct horse battery staple", salt, params)
        val a = Device("device-a", E2eSealer(key))
        val b = Device("device-b", E2eSealer(E2eKeys.derive("correct horse battery staple", salt, params)))
        a.srs.saveNote("k:水", myStory = "secret mnemonic")
        a.sync()

        val stored = server.changes.single()
        assertNull(stored.row)
        val sealed = assertNotNull(stored.sealed)
        assertFalse(Base64.decode(sealed).decodeToString(throwOnInvalidSequence = false).contains("secret mnemonic"))

        b.sync()
        assertEquals("secret mnemonic", b.srs.note("k:水").myStory)

        val wrong = Device("device-c", E2eSealer(E2eKeys.derive("wrong passphrase", salt, params)))
        assertFailsWith<SyncException> { wrong.sync() }
        val noKey = Device("device-d")
        assertFailsWith<SyncException> { noKey.sync() }
    }

    @Test
    fun verifierDetectsWrongPassphrase() {
        val salt = E2eKeys.newSalt()
        val params = E2eKeys.Params(iterations = 1, memoryKiB = 64)
        val key = E2eKeys.derive("pass", salt, params)
        val verifier = E2eKeys.verifier(key)
        assertTrue(E2eKeys.matches(key, verifier))
        assertFalse(E2eKeys.matches(E2eKeys.derive("nope", salt, params), verifier))
    }
}
