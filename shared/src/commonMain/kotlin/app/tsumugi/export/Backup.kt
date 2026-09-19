package app.tsumugi.export

import app.cash.sqldelight.db.SqlDriver
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.srs.SrsRepository
import app.tsumugi.sync.Change
import app.tsumugi.sync.ChangeApplier
import app.tsumugi.sync.TableSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Clock

/**
 * A full JSON snapshot of the learner's synced data (BRIEF_V2 G-10, DECISIONS D-116): every synced table exactly as
 * the sync protocol carries it, tombstoned reviews included (so an undo survives a restore), plus suspended flags.
 * Device-local data (reader documents, recordings, settings of this device, caches) is not in it.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** The user-database schema version that wrote it (informational; rows are read by column name). */
    val schemaVersion: Long,
    val exportedAt: Long,
    val deviceId: String,
    /** Table name → rows (column → value), in the shape of docs/SYNC_PROTOCOL.md rows. */
    val tables: Map<String, List<JsonObject>>,
    /** Suspended cards: {id, item_id, direction, suspended, updated_at}. */
    val cardFlags: List<JsonObject> = emptyList(),
) {
    companion object {
        const val FORMAT = "tsumugi-backup"
        const val VERSION = 1
    }
}

data class RestoreProgress(val done: Int, val total: Int)

data class RestoreResult(
    /** Rows that changed local data. */
    val applied: Int,
    /** Rows already present (or older than the local copy). */
    val unchanged: Int,
    val cardsRecomputed: Int,
    /** Synced settings keys that changed (the app reloads the scheduler for srs.* keys). */
    val changedSettings: Set<String>,
)

class BackupFormatException(message: String) : Exception(message)

/**
 * Export and restore of [BackupFile]. Restore **merges** through the sync merge rules ([ChangeApplier]) instead of
 * replacing: reviews by union (never deleting a review, rule 12), path progress by MAX (never lowering a level,
 * rule 11), everything else last-writer-wins. So restoring an old backup onto a newer database can only add what
 * is missing, and restoring twice is a no-op. Restored rows are recorded for sync like local edits, so a
 * signed-in device pushes them. There is no "replace" mode: wiping would physically delete synced reviews.
 */
class BackupService(
    private val driver: SqlDriver,
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Throws(Exception::class)
    suspend fun snapshot(): BackupFile = withContext(Dispatchers.IO) {
        db.transactionWithResult {
            BackupFile(
                schemaVersion = TsumugiDatabase.Schema.version,
                exportedAt = clock.now().toEpochMilliseconds(),
                deviceId = deviceId,
                tables = ORDER.associate { it.name to it.readAll(driver) },
                cardFlags = cardFlags(),
            )
        }
    }

    @Throws(Exception::class)
    suspend fun exportJson(): String {
        val file = snapshot()
        return withContext(Dispatchers.Default) { json.encodeToString(BackupFile.serializer(), file) }
    }

    /** Writes the backup to [outPath]; returns the number of rows. */
    @Throws(Exception::class)
    suspend fun exportTo(fs: FileSystem, outPath: String): Int {
        val file = snapshot()
        withContext(Dispatchers.IO) { fs.write(outPath.toPath()) { writeUtf8(json.encodeToString(BackupFile.serializer(), file)) } }
        return file.tables.values.sumOf { it.size } + file.cardFlags.size
    }

    @Throws(Exception::class)
    suspend fun restoreFrom(fs: FileSystem, path: String, onProgress: (RestoreProgress) -> Unit = {}): RestoreResult =
        restore(withContext(Dispatchers.IO) { fs.read(path.toPath()) { readUtf8() } }, onProgress)

    /** Parses and merges a backup (see the class comment). */
    @Throws(Exception::class)
    suspend fun restore(text: String, onProgress: (RestoreProgress) -> Unit = {}): RestoreResult {
        val file = withContext(Dispatchers.Default) {
            runCatching { json.decodeFromString(BackupFile.serializer(), text) }.getOrElse { throw BackupFormatException("Not a Tsumugi backup: ${it.message}") }
        }
        if (file.format != BackupFile.FORMAT) throw BackupFormatException("Not a Tsumugi backup (format ${file.format})")
        if (file.version > BackupFile.VERSION) throw BackupFormatException("This backup is from a newer version of Tsumugi; update the app first")

        val changes = ArrayList<Pair<Change, JsonObject>>()
        for (spec in ORDER) {
            for (row in file.tables[spec.name].orEmpty()) {
                changes += Change(spec.name, spec.keyOf(row), Change.UPSERT, row, updatedAt = spec.updatedAtOf(row), deviceId = file.deviceId) to row
            }
        }
        for (row in file.cardFlags) {
            val id = (row["id"] as? JsonPrimitive)?.content ?: continue
            val at = (row["updated_at"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            changes += Change(TableSpec.CARD_FLAGS, id, Change.UPSERT, row, updatedAt = at, deviceId = file.deviceId) to row
        }

        val applier = ChangeApplier(driver, db, deviceId)
        val affected = LinkedHashSet<String>()
        val settings = LinkedHashSet<String>()
        var applied = 0
        onProgress(RestoreProgress(0, changes.size))
        changes.chunked(BATCH).forEachIndexed { i, batch ->
            withContext(Dispatchers.IO) {
                db.transaction {
                    for ((c, row) in batch) {
                        // Count real changes: the merge rules report reviews and union rows as applied even when
                        // they were already here (INSERT OR IGNORE), so compare the row before and after.
                        val before = current(c)
                        val touched = LinkedHashSet<String>()
                        applier.apply(c, row, touched)
                        if (current(c) != before) {
                            applied++
                            affected += touched
                            if (c.table == TableSpec.setting.name) settings += c.key
                        }
                    }
                }
            }
            onProgress(RestoreProgress(minOf((i + 1) * BATCH, changes.size), changes.size))
        }
        affected.forEach { srs.recomputeCard(it) }
        return RestoreResult(applied, changes.size - applied, affected.size, settings)
    }

    /** The local state a change would touch: the row, or the card's suspended flag. */
    private fun current(c: Change): Any? =
        if (c.table == TableSpec.CARD_FLAGS) db.srsQueries.cardById(c.key).executeAsOneOrNull()?.suspended
        else TableSpec.all[c.table]?.read(driver, c.key)

    /** Suspended cards in the card_flags wire shape (only the learner's suspend choice syncs; FSRS state is rebuilt). */
    private fun cardFlags(): List<JsonObject> =
        driver.executeQuery(null, "SELECT id, item_id, direction, suspended, updated_at FROM card WHERE suspended != 0 ORDER BY id", { c ->
            val out = ArrayList<JsonObject>()
            while (c.next().value) {
                out += JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(c.getString(0)), "item_id" to JsonPrimitive(c.getString(1)),
                        "direction" to JsonPrimitive(c.getString(2)), "suspended" to JsonPrimitive(c.getLong(3)),
                        "updated_at" to JsonPrimitive(c.getLong(4)),
                    ),
                )
            }
            app.cash.sqldelight.db.QueryResult.Value(out)
        }, 0).value

    companion object {
        private const val BATCH = 500

        /** Parents before children (items before relations and reviews), reviews last. */
        internal val ORDER: List<TableSpec> = listOf(
            TableSpec.item, TableSpec.itemRelation, TableSpec.note, TableSpec.setting, TableSpec.wordList,
            TableSpec.wordListEntry, TableSpec.pathProgress, TableSpec.pathUnlock, TableSpec.examAttempt, TableSpec.review,
            TableSpec.immersionSession, TableSpec.readerAnnotation,
        )
    }
}
