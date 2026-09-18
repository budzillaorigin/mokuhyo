package app.tsumugi.sync

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * - **pull**: remote changes are applied with the triggers silenced — reviews by set union, relations by union,
 *   everything else last-writer-wins by (updatedAt, deviceId) with tombstones — then every card whose reviews
 *   changed is recomputed from its full review history, so all devices converge on identical FSRS state.
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

    private val _status = MutableStateFlow(SyncStatus(lastSyncedAt = meta.get(LAST_SYNCED_AT).executeAsOneOrNull()?.toLongOrNull()))
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val lastSeq: Long get() = meta.get(LAST_SEQ).executeAsOneOrNull()?.toLongOrNull() ?: 0L

    fun pendingChanges(): Int = q.unsyncedCount().executeAsOne().toInt()

    /** Push local changes, then pull and merge remote ones. */
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

    suspend fun push(): Int = lock.withLock { pushLocked() }

    suspend fun pull(): Int = lock.withLock { pullLocked().first }

    // --- Push ---------------------------------------------------------------------------------------------

    private suspend fun pushLocked(): Int {
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
            // Reviews and relations are append-only: one that vanished before it was pushed (an undo) is dropped.
            if (spec.merge == MergeRule.UNION) return null
            q.putRowVersion(table, key, markedAt, deviceId)
            return change(table, key, Change.DELETE, null, markedAt)
        }
        val updatedAt = spec.updatedAtOf(row)
        if (spec.merge == MergeRule.LWW) q.putRowVersion(table, key, updatedAt, deviceId)
        return change(table, key, Change.UPSERT, row, updatedAt)
    }

    private fun change(table: String, key: String, op: String, row: JsonObject?, updatedAt: Long): Change {
        val s = sealer
        return if (s != null && row != null) {
            Change(table, key, op, sealed = s.seal(row.toString()), updatedAt = updatedAt, deviceId = deviceId)
        } else {
            Change(table, key, op, row = row, updatedAt = updatedAt, deviceId = deviceId)
        }
    }

    // --- Pull ---------------------------------------------------------------------------------------------

    private suspend fun pullLocked(): Pair<Int, Int> {
        var since = lastSeq
        var applied = 0
        val affectedCards = LinkedHashSet<String>()
        while (true) {
            val page = transport.pull(since, PULL_PAGE)
            if (page.changes.isNotEmpty()) {
                val rows = page.changes.map { it to decode(it) }
                withContext(Dispatchers.IO) {
                    db.transaction {
                        q.setApplying(1)
                        rows.forEach { (c, row) -> apply(c, row, affectedCards) }
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
        return applied to affectedCards.size
    }

    private fun decode(c: Change): JsonObject? {
        if (c.op == Change.DELETE) return null
        c.row?.let { return it }
        val sealed = c.sealed ?: throw SyncException("Change ${c.table}/${c.key} has no payload")
        val s = sealer ?: throw SyncException("This account uses end-to-end encryption: enter your sync passphrase")
        val plain = s.open(sealed) ?: throw SyncException("Couldn't decrypt synced data: wrong passphrase?")
        return SyncJson.decodeFromString(JsonObject.serializer(), plain)
    }

    private fun apply(c: Change, row: JsonObject?, affectedCards: MutableSet<String>) {
        when (c.table) {
            TableSpec.CARD_FLAGS -> {
                // A local, unpushed flag change is newer from this device's point of view: keep it.
                if (q.hasUnsyncedMarker(TableSpec.CARD_FLAGS, c.key).executeAsOne() > 0 || row == null) return
                ensureCard(c.key, c.updatedAt)
                val suspended = (row["suspended"] as? JsonPrimitive)?.longOrNull ?: 0L
                db.srsQueries.setSuspended(suspended, c.updatedAt, c.key)
            }
            TableSpec.review.name -> {
                if (row == null) return
                val cardId = (row["card_id"] as? JsonPrimitive)?.content ?: return
                ensureCard(cardId, c.updatedAt)
                TableSpec.review.write(driver, row, orIgnore = true)
                affectedCards += cardId
            }
            TableSpec.itemRelation.name -> if (row != null) TableSpec.itemRelation.write(driver, row, orIgnore = true)
            else -> {
                val spec = TableSpec.all[c.table] ?: return
                val local = spec.read(driver, c.key)
                if (local == null && c.op == Change.DELETE) return
                if (local != null && !newer(c, spec, local)) return
                if (c.op == Change.DELETE) spec.delete(driver, c.key) else spec.write(driver, row ?: return)
                q.putRowVersion(c.table, c.key, c.updatedAt, c.deviceId)
            }
        }
    }

    /** Last writer wins: compare (updatedAt, deviceId) lexicographically against the local copy. */
    private fun newer(c: Change, spec: TableSpec, local: JsonObject): Boolean {
        val localUpdated = spec.updatedAtOf(local)
        val version = q.rowVersion(c.table, c.key).executeAsOneOrNull()
        // If the local row was last written by a remote device we know which one; otherwise it's ours.
        val localDevice = version?.takeIf { it.updated_at == localUpdated }?.device_id ?: deviceId
        return when {
            c.updatedAt != localUpdated -> c.updatedAt > localUpdated
            else -> c.deviceId > localDevice
        }
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
    }
}
