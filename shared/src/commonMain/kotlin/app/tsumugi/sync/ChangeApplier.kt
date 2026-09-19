package app.tsumugi.sync

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The merge rules of sync (docs/SYNC_PROTOCOL.md), applied to one change at a time: reviews by set union plus
 * tombstones, relations/unlocks/exam attempts by union, path progress by MAX, card flags unless a local change is
 * pending, everything else last-writer-wins by (updatedAt, deviceId). Used by [SyncEngine] for pulled changes and
 * by the JSON backup restore ([app.tsumugi.export.BackupService], DECISIONS D-116), so a restore merges exactly
 * like a sync would. The caller owns the transaction and the `applying` flag.
 */
internal class ChangeApplier(
    private val driver: SqlDriver,
    private val db: TsumugiDatabase,
    private val deviceId: String,
) {
    private val q get() = db.syncQueries

    /** Applies one change; true when local data changed. Card ids whose reviews changed are added to [affectedCards]. */
    fun apply(c: Change, row: JsonObject?, affectedCards: MutableSet<String>): Boolean {
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
                if (spec.merge == MergeRule.UNION_TOMBSTONE) {
                    // Other append-only logs with a tombstone (immersion sessions, D-167): union, earliest tombstone wins.
                    if (row == null) return false
                    spec.write(driver, row, orIgnore = true)
                    spec.tombstoneOf(row)?.let { spec.applyTombstone(driver, c.key, it) }
                    return true
                }
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
}
