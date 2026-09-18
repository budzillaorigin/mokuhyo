package app.tsumugi.sync

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.settings.DeviceSettings
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathProgressStore
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
        // F-37: the row key is an opaque keyed hash, stable per (table, key) so the server can still dedupe.
        assertFalse(stored.key.contains("水"), "key travels hashed: ${stored.key}")
        assertEquals(E2eSealer(key).keyId("note", "k:水"), stored.key)
        assertTrue(E2eSealer(key).keyId("setting", "k:水") != stored.key, "same key in another table gets another id")

        b.sync()
        assertEquals("secret mnemonic", b.srs.note("k:水").myStory)

        // LWW and deletes still merge on the real key inside the envelope.
        clock.advance(1.seconds)
        b.srs.saveNote("k:水", myStory = "edited on B")
        a.settings.put("today.budgetMinutes", "40")
        b.sync(); a.sync()
        assertEquals("edited on B", a.srs.note("k:水").myStory)
        assertEquals("40", b.let { it.sync(); it.settings.get("today.budgetMinutes") })
        a.db.userQueries.insertList("list-e2e", "Food", clock.now().toEpochMilliseconds(), clock.now().toEpochMilliseconds())
        a.sync(); b.sync()
        clock.advance(1.seconds)
        a.db.userQueries.deleteList(clock.now().toEpochMilliseconds(), "list-e2e")
        a.sync(); b.sync()
        assertTrue(b.db.userQueries.lists().executeAsList().isEmpty())
        assertTrue(server.changes.none { it.key.contains("list-e2e") || it.key.contains("today") })

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

    // --- BRIEF_V2 F-05: review tombstones -------------------------------------------------------------------

    private fun Device.liveReviews() = db.srsQueries.allReviews().executeAsList().map { it.id }.toSet()

    @Test
    fun undoAfterPushConverges() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        a.srs.addItems(listOf(water))
        a.srs.introduce(listOf(meaning))
        clock.advance(10.minutes)
        a.srs.review(meaning, Rating.GOOD)
        val before = a.srs.card(meaning)!!
        clock.advance(1.minutes)
        val outcome = a.srs.review(meaning, Rating.AGAIN, correct = false)
        a.sync() // the AGAIN is on the server now
        b.sync()
        assertEquals(a.liveReviews(), b.liveReviews())
        assertEquals(3, b.liveReviews().size)

        clock.advance(5.seconds)
        a.srs.undo(outcome) // after the push: must tombstone, not delete
        assertNotNull(a.db.srsQueries.reviewById(outcome.reviewId).executeAsOneOrNull()?.deleted_at, "tombstoned locally")
        a.sync(); b.sync(); a.sync()

        assertEquals(a.liveReviews(), b.liveReviews(), "the undo reached the other device")
        assertEquals(2, b.liveReviews().size)
        assertEquals(before.fsrs, a.card(meaning))
        assertEquals(a.card(meaning), b.card(meaning), "both replay the same live history")
        val tombstones = listOf(a, b).map { it.db.srsQueries.reviewById(outcome.reviewId).executeAsOne().deleted_at }
        assertEquals(tombstones[0], tombstones[1])
        assertEquals(0, a.engine.pendingChanges())
        // A device that joins later gets the row already tombstoned.
        val c = Device("device-c")
        c.sync()
        assertEquals(a.liveReviews(), c.liveReviews())
        assertEquals(a.card(meaning), c.card(meaning))
    }

    @Test
    fun undoBeforePushNeverLeavesTheDevice() = runTest {
        val a = Device("device-a")
        a.srs.addItems(listOf(water))
        a.srs.introduce(listOf(meaning))
        clock.advance(10.minutes)
        val outcome = a.srs.review(meaning, Rating.GOOD)
        a.srs.undo(outcome)
        assertNull(a.db.srsQueries.reviewById(outcome.reviewId).executeAsOneOrNull(), "never pushed: fast-path delete")
        a.sync()
        assertTrue(server.changes.none { it.key == outcome.reviewId })
    }

    @Test
    fun undoDuringAPushTombstones() = runTest {
        val a = Device("device-a")
        a.srs.addItems(listOf(water))
        a.srs.introduce(listOf(meaning))
        clock.advance(10.minutes)
        val outcome = a.srs.review(meaning, Rating.GOOD)
        a.db.syncQueries.setPushing(1) // rows are on the wire; markers not yet marked synced
        a.srs.undo(outcome)
        a.db.syncQueries.setPushing(0)
        assertNotNull(a.db.srsQueries.reviewById(outcome.reviewId).executeAsOneOrNull()?.deleted_at)
    }

    // --- BRIEF_V2 F-04: path progress merges to the higher level ---------------------------------------------

    @Test
    fun pathProgressMergesToTheHigherLevel() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        val pa = PathProgressStore(a.db, clock)
        val pb = PathProgressStore(b.db, clock)
        pb.recordPassed(6)
        clock.advance(1.seconds)
        pa.recordPassed(4) // written later, but lower: last-writer-wins would regress B
        pa.addUnlocks(listOf("k:林"), manual = true)
        pb.addUnlocks(listOf("k:森"), manual = false)
        a.sync(); b.sync(); a.sync()
        assertEquals(6, pa.progress().passedLevel)
        assertEquals(6, pb.progress().passedLevel)
        assertEquals(setOf("k:林", "k:森"), pa.unlocks().map { it.itemId }.toSet())
        assertEquals(pa.unlocks().toSet(), pb.unlocks().toSet())

        // An explicit reset is the one way down, and it wins everywhere.
        clock.advance(1.seconds)
        pa.resetTo(3)
        a.sync(); b.sync()
        assertEquals(2, pb.progress().passedLevel)
        // A stale device that passes a level again after the reset climbs from there.
        pb.recordPassed(3)
        b.sync(); a.sync()
        assertEquals(3, pa.progress().passedLevel)
    }

    // --- BRIEF_V2 F-31 / F-32: device settings stay local; synced weights reach the app ------------------------

    @Test
    fun deviceSettingsNeverSync() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        DeviceSettings(a.db).put("ai.endpoint_url", "http://<lan-ip>:11434")
        a.settings.put("today.budgetMinutes", "40")
        a.sync(); b.sync()
        assertNull(DeviceSettings(b.db).get("ai.endpoint_url"))
        assertEquals("40", b.settings.get("today.budgetMinutes"))
        assertTrue(server.changes.none { it.table == "device_setting" })
    }

    @Test
    fun pulledSettingsAreReportedToTheApp() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        val seen = ArrayList<Set<String>>()
        b.engine.onSettingsChanged = { seen += it }
        a.settings.put(SettingsRepository.FSRS_WEIGHTS, "[]")
        a.sync(); b.sync()
        assertEquals(listOf(setOf(SettingsRepository.FSRS_WEIGHTS)), seen)
        b.sync()
        assertEquals(1, seen.size, "nothing new, no call")
    }
}
