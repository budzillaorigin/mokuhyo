package app.tsumugi.sync

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.time.Clock

enum class SyncState { IDLE, SYNCING, ERROR }

data class SyncStatus(
    val state: SyncState = SyncState.IDLE,
    /** Epoch ms of the last successful sync. */
    val lastSyncedAt: Long? = null,
    /** Local changes not yet pushed. */
    val pending: Int = 0,
    val error: String? = null,
)

data class SyncResult(val pushed: Int, val pulled: Int, val cardsRecomputed: Int)

/**
 * The client side of sync (BRIEF §8.2, docs/SYNC_PROTOCOL.md):
 * - **push**: triggers in sync.sq record dirty-row markers; pushing serializes each dirty row's *current* state
 *   (or a DELETE when the row is gone) and marks the markers synced once the server has accepted them;
 * - **pull**: remote changes are applied with the triggers silenced — reviews by set union plus tombstones,
 *   relations and unlocks by union, path progress by MAX, everything else last-writer-wins by (updatedAt, deviceId)
 *   with tombstones — then every card whose reviews changed is recomputed from its live review history, so all
 *   devices converge on identical FSRS state.
 */
class SyncEngine(
    private val driver: SqlDriver,
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val deviceId: String,
    private val transport: SyncTransport,
    /** End-to-end sealing; null = payloads in clear. */
    var sealer: Sealer? = null,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.syncQueries
    private val meta get() = db.metaQueries
    private val lock = Mutex()

    /**
     * Called after a pull that changed synced settings, with the changed keys (e.g. so the app reloads the FSRS
     * scheduler when `srs.fsrsWeights` arrived from another device, BRIEF_V2 F-32).
     */
    var onSettingsChanged: (suspend (Set<String>) -> Unit)? = null

    init {
        // A push interrupted by a crash leaves the flag set; nothing is on the wire any more.
        q.setPushing(0)
    }

    private val _status = MutableStateFlow(SyncStatus(lastSyncedAt = meta.get(LAST_SYNCED_AT).executeAsOneOrNull()?.toLongOrNull()))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val lastSeq: Long get() = meta.get(LAST_SEQ).executeAsOneOrNull()?.toLongOrNull() ?: 0L

    fun pendingChanges(): Int = q.unsyncedCount().executeAsOne().toInt()

    /** Push local changes, then pull and merge remote ones. */
    @Throws(Exception::class)
    suspend fun sync(): SyncResult = lock.withLock {
        _status.value = _status.value.copy(state = SyncState.SYNCING, error = null)
        try {
            val pushed = pushLocked()
            val (pulled, recomputed) = pullLocked()
            val now = clock.now().toEpochMilliseconds()
            meta.put(LAST_SYNCED_AT, now.toString())
            _status.value = SyncStatus(SyncState.IDLE, now, pendingChanges(), null)
            SyncResult(pushed, pulled, recomputed)
        } catch (e: Exception) {
            _status.value = _status.value.copy(state = SyncState.ERROR, pending = pendingChanges(), error = e.message ?: e::class.simpleName)
            throw e
        }
    }

    @Throws(Exception::class)
    suspend fun push(): Int = lock.withLock { pushLocked() }

    @Throws(Exception::class)
    suspend fun pull(): Int = lock.withLock { pullLocked().first }

    // --- Push ---------------------------------------------------------------------------------------------

    private suspend fun pushLocked(): Int {
        // From here until the markers are marked synced, serialized rows may be on the wire: an undo must
        // tombstone instead of taking the physical-delete fast path (SrsRepository.undo, DECISIONS D-042).
        withContext(Dispatchers.IO) { q.setPushing(1) }
        try {
            val upTo = withContext(Dispatchers.IO) { q.maxUnsyncedSeq().executeAsOne().max_seq } ?: return 0
            val changes = withContext(Dispatchers.IO) {
                val markers = q.unsyncedMarkers(upTo).executeAsList()
                // Latest marker per row: the row's current state covers every earlier change to it.
                val latest = LinkedHashMap<Pair<String, String>, app.tsumugi.db.Change_log>()
                markers.forEach { latest[it.table_name to it.row_key] = it }
                db.transactionWithResult {
                    latest.values.sortedBy { it.seq }.mapNotNull { marker -> outgoing(marker.table_name, marker.row_key, marker.created_at) }
                }
            }
            for (batch in changes.chunked(MAX_PUSH)) transport.push(batch)
            withContext(Dispatchers.IO) {
                db.transaction {
                    q.markSyncedUpTo(upTo)
                    q.purgeSynced()
                }
            }
            return changes.size
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { q.setPushing(0) }
        }
    }

    private fun outgoing(table: String, key: String, markedAt: Long): Change? {
        if (table == TableSpec.CARD_FLAGS) {
            val card = db.srsQueries.cardById(key).executeAsOneOrNull() ?: return null
            val row = JsonObject(
                mapOf(
                    "id" to JsonPrimitive(card.id), "item_id" to JsonPrimitive(card.item_id),
                    "direction" to JsonPrimitive(card.direction), "suspended" to JsonPrimitive(card.suspended),
                ),
            )
            q.putRowVersion(table, key, markedAt, deviceId)
            return change(table, key, Change.UPSERT, row, markedAt)
        }
        val spec = TableSpec.all[table] ?: return null
        val row = spec.read(driver, key)
        if (row == null) {
            // Append-only rows vanish only through the never-pushed fast path (an undo before any push): drop them.
            if (spec.merge == MergeRule.UNION || spec.merge == MergeRule.UNION_TOMBSTONE) return null
            q.putRowVersion(table, key, markedAt, deviceId)
            return change(table, key, Change.DELETE, null, markedAt)
        }
        val updatedAt = spec.updatedAtOf(row)
        if (spec.merge == MergeRule.LWW || spec.merge == MergeRule.MAX) q.putRowVersion(table, key, updatedAt, deviceId)
        return change(table, key, Change.UPSERT, row, updatedAt)
    }

    /**
     * Builds the wire record. With end-to-end encryption the server sees only an opaque key id (a keyed hash of
     * table and key, [Sealer.keyId]) and the sealed envelope `{"v":2,"key":…,"row":…}` carries the real key, for
     * DELETEs too (protocol v2, docs/SYNC_PROTOCOL.md).
     */
    private fun change(table: String, key: String, op: String, row: JsonObject?, updatedAt: Long): Change {
        val s = sealer ?: return Change(table, key, op, row = row, updatedAt = updatedAt, deviceId = deviceId)
        val envelope = JsonObject(mapOf("v" to JsonPrimitive(ENVELOPE_VERSION), "key" to JsonPrimitive(key), "row" to (row ?: JsonNull)))
        return Change(table, s.keyId(table, key), op, sealed = s.seal(envelope.toString()), updatedAt = updatedAt, deviceId = deviceId)
    }

    // --- Pull ---------------------------------------------------------------------------------------------

    private suspend fun pullLocked(): Pair<Int, Int> {
        var since = lastSeq
        var applied = 0
        val affectedCards = LinkedHashSet<String>()
        val changedSettings = LinkedHashSet<String>()
        while (true) {
            val page = transport.pull(since, PULL_PAGE)
            if (page.changes.isNotEmpty()) {
                val rows = page.changes.map { decode(it) }
                withContext(Dispatchers.IO) {
                    db.transaction {
                        q.setApplying(1)
                        rows.forEach { (c, row) -> if (apply(c, row, affectedCards) && c.table == TableSpec.setting.name) changedSettings += c.key }
                        q.setApplying(0)
                        meta.put(LAST_SEQ, page.lastSeq.toString())
                    }
                }
                applied += page.changes.size
            } else if (page.lastSeq > since) {
                withContext(Dispatchers.IO) { meta.put(LAST_SEQ, page.lastSeq.toString()) }
            }
            since = page.lastSeq
            if (!page.hasMore) break
        }
        affectedCards.forEach { srs.recomputeCard(it) }
        if (changedSettings.isNotEmpty()) onSettingsChanged?.invoke(changedSettings)
        return applied to affectedCards.size
    }

    /** The change with its real key, and its row (null for DELETE). Opens sealed payloads (v2 envelope or v1 row). */
    private fun decode(c: Change): Pair<Change, JsonObject?> {
        if (c.sealed == null) {
            if (c.op == Change.DELETE) return c to null
            return c to (c.row ?: throw SyncException("Change ${c.table}/${c.key} has no payload"))
        }
        val s = sealer ?: throw SyncException("This account uses end-to-end encryption: enter your sync passphrase")
        val plain = s.open(c.sealed) ?: throw SyncException("Couldn't decrypt synced data: wrong passphrase?")
        val json = SyncJson.decodeFromString(JsonObject.serializer(), plain)
        val version = (json["v"] as? JsonPrimitive)?.intOrNull
        val realKey = (json["key"] as? JsonPrimitive)?.content
        if (version == ENVELOPE_VERSION && realKey != null) {
            val row = json["row"] as? JsonObject
            if (s.keyId(c.table, realKey) != c.key) throw SyncException("Synced change ${c.table} failed its key check")
            return c.copy(key = realKey, sealed = null, row = row) to (if (c.op == Change.DELETE) null else row)
        }
        // Protocol v1: the sealed payload is the row itself and the key travelled in clear.
        return c to (if (c.op == Change.DELETE) null else json)
    }

    /** Applies one remote change; true when local data changed. */
    private fun apply(c: Change, row: JsonObject?, affectedCards: MutableSet<String>): Boolean {
        when (c.table) {
            TableSpec.CARD_FLAGS -> {
                // A local, unpushed flag change is newer from this device's point of view: keep it.
                if (q.hasUnsyncedMarker(TableSpec.CARD_FLAGS, c.key).executeAsOne() > 0 || row == null) return false
                ensureCard(c.key, c.updatedAt)
                val suspended = (row["suspended"] as? JsonPrimitive)?.longOrNull ?: 0L
                db.srsQueries.setSuspended(suspended, c.updatedAt, c.key)
                return true
            }
            TableSpec.review.name -> {
                if (row == null) return false
                val cardId = (row["card_id"] as? JsonPrimitive)?.content ?: return false
                ensureCard(cardId, c.updatedAt)
                TableSpec.review.write(driver, row, orIgnore = true)
                TableSpec.review.tombstoneOf(row)?.let { TableSpec.review.applyTombstone(driver, c.key, it) }
                affectedCards += cardId
                return true
            }
            else -> {
                val spec = TableSpec.all[c.table] ?: return false
                if (spec.merge == MergeRule.UNION) {
                    if (row != null) spec.write(driver, row, orIgnore = true)
                    return row != null
                }
                val local = spec.read(driver, c.key)
                if (local == null && c.op == Change.DELETE) return false
                if (local != null && !wins(c, spec, row, local)) return false
                if (c.op == Change.DELETE) spec.delete(driver, c.key) else spec.write(driver, row ?: return false)
                q.putRowVersion(c.table, c.key, c.updatedAt, c.deviceId)
                return true
            }
        }
    }

    /** Whether the remote change beats the local row: MAX by rank tuple, otherwise last writer wins. */
    private fun wins(c: Change, spec: TableSpec, remote: JsonObject?, local: JsonObject): Boolean {
        if (spec.merge == MergeRule.MAX && remote != null) {
            val cmp = compareRanks(spec.rankOf(remote), spec.rankOf(local))
            if (cmp != 0) return cmp > 0
            return c.deviceId > localDevice(c, spec, local)
        }
        return newer(c, spec, local)
    }

    /** Last writer wins: compare (updatedAt, deviceId) lexicographically against the local copy. */
    private fun newer(c: Change, spec: TableSpec, local: JsonObject): Boolean {
        val localUpdated = spec.updatedAtOf(local)
        return when {
            c.updatedAt != localUpdated -> c.updatedAt > localUpdated
            else -> c.deviceId > localDevice(c, spec, local)
        }
    }

    /** If the local row was last written by a remote device we know which one; otherwise it's ours. */
    private fun localDevice(c: Change, spec: TableSpec, local: JsonObject): String {
        val version = q.rowVersion(c.table, c.key).executeAsOneOrNull()
        return version?.takeIf { it.updated_at == spec.updatedAtOf(local) }?.device_id ?: deviceId
    }

    private fun compareRanks(a: List<Long>, b: List<Long>): Int {
        for (i in a.indices) {
            val cmp = a[i].compareTo(b.getOrElse(i) { 0L })
            if (cmp != 0) return cmp
        }
        return 0
    }

    /** Card ids are "<itemId>#<DIRECTION>"; make sure the row exists so it can be recomputed from reviews. */
    private fun ensureCard(cardId: String, at: Long) {
        val itemId = cardId.substringBeforeLast('#', missingDelimiterValue = "")
        val direction = cardId.substringAfterLast('#', missingDelimiterValue = "")
        if (itemId.isEmpty() || direction.isEmpty()) return
        db.srsQueries.insertCardIfAbsent(cardId, itemId, direction, at, at, at)
    }

    companion object {
        const val MAX_PUSH = 5_000
        const val PULL_PAGE = 1_000
        const val LAST_SEQ = "sync.lastSeq"
        const val LAST_SYNCED_AT = "sync.lastSyncedAt"
        /** Sealed-envelope format (protocol v2): the real key travels inside the ciphertext. */
        const val ENVELOPE_VERSION = 2
    }
}
