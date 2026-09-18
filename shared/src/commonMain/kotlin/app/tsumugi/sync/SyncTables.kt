package app.tsumugi.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

internal enum class ColumnType { TEXT, INTEGER, REAL }

internal enum class MergeRule {
    /** Immutable facts merged by set union (reviews, relations). */
    UNION,
    /** Last writer wins by (updatedAt, deviceId). */
    LWW,
}

/** How one synced table maps to wire rows (docs/SYNC_PROTOCOL.md "Synced tables"). */
internal class TableSpec(
    val name: String,
    val columns: List<Pair<String, ColumnType>>,
    val keys: List<String>,
    val updatedAt: String?,
    val merge: MergeRule,
) {
    private val columnList = columns.joinToString(", ") { it.first }
    private val keyWhere = keys.joinToString(" AND ") { "$it = ?" }
    private val types = columns.toMap()

    fun splitKey(key: String): List<String> = key.split(KEY_SEPARATOR)

    fun read(driver: SqlDriver, key: String): JsonObject? {
        val parts = splitKey(key)
        return driver.executeQuery(null, "SELECT $columnList FROM $name WHERE $keyWhere", { cursor ->
            QueryResult.Value(if (cursor.next().value) rowOf(cursor) else null)
        }, keys.size) { parts.forEachIndexed { i, v -> bindString(i, v) } }.value
    }

    fun write(driver: SqlDriver, row: JsonObject, orIgnore: Boolean = false) {
        val placeholders = columns.joinToString(", ") { "?" }
        val verb = if (orIgnore) "INSERT OR IGNORE" else "INSERT OR REPLACE"
        driver.execute(null, "$verb INTO $name ($columnList) VALUES ($placeholders)", columns.size) {
            columns.forEachIndexed { i, (column, type) -> bind(i, type, row[column]) }
        }
    }

    fun delete(driver: SqlDriver, key: String) {
        val parts = splitKey(key)
        driver.execute(null, "DELETE FROM $name WHERE $keyWhere", keys.size) { parts.forEachIndexed { i, v -> bindString(i, v) } }
    }

    fun updatedAtOf(row: JsonObject): Long = updatedAt?.let { (row[it] as? JsonPrimitive)?.longOrNull } ?: 0L

    private fun rowOf(cursor: SqlCursor): JsonObject = JsonObject(
        columns.mapIndexed { i, (column, type) ->
            column to when (type) {
                ColumnType.TEXT -> cursor.getString(i)?.let(::JsonPrimitive) ?: JsonNull
                ColumnType.INTEGER -> cursor.getLong(i)?.let(::JsonPrimitive) ?: JsonNull
                ColumnType.REAL -> cursor.getDouble(i)?.let(::JsonPrimitive) ?: JsonNull
            }
        }.toMap(),
    )

    private fun SqlPreparedStatement.bind(index: Int, type: ColumnType, value: Any?) {
        val p = value as? JsonPrimitive
        if (p == null || p is JsonNull) {
            when (type) {
                ColumnType.TEXT -> bindString(index, null)
                ColumnType.INTEGER -> bindLong(index, null)
                ColumnType.REAL -> bindDouble(index, null)
            }
            return
        }
        when (type) {
            ColumnType.TEXT -> bindString(index, p.content)
            ColumnType.INTEGER -> bindLong(index, p.longOrNull)
            ColumnType.REAL -> bindDouble(index, p.doubleOrNull)
        }
    }

    companion object {
        const val KEY_SEPARATOR = ""
        private val T = ColumnType.TEXT
        private val I = ColumnType.INTEGER

        val item = TableSpec(
            "item",
            listOf(
                "id" to T, "kind" to T, "level" to I, "jlpt" to I, "ilr" to T, "primary_text" to T, "reading" to T,
                "meanings" to T, "accepted_readings" to T, "source" to T, "pack_id" to T, "ref_id" to T, "context" to T,
                "created_at" to I, "updated_at" to I, "deleted" to I,
            ),
            listOf("id"), "updated_at", MergeRule.LWW,
        )
        val itemRelation = TableSpec(
            "item_relation", listOf("parent_id" to T, "child_id" to T, "kind" to T),
            listOf("parent_id", "child_id", "kind"), null, MergeRule.UNION,
        )
        val review = TableSpec(
            "review",
            listOf(
                "id" to T, "card_id" to T, "ts" to I, "rating" to I, "elapsed_ms" to I, "answer_text" to T,
                "correct" to I, "device_id" to T, "source" to T,
            ),
            listOf("id"), "ts", MergeRule.UNION,
        )
        val note = TableSpec(
            "note",
            listOf(
                "item_id" to T, "my_story" to T, "synonyms" to T, "tags" to T, "mnemonic_image_path" to T,
                "user_audio_path" to T, "updated_at" to I,
            ),
            listOf("item_id"), "updated_at", MergeRule.LWW,
        )
        val setting = TableSpec("setting", listOf("key" to T, "value" to T, "updated_at" to I), listOf("key"), "updated_at", MergeRule.LWW)
        val wordList = TableSpec(
            "word_list", listOf("id" to T, "name" to T, "created_at" to I, "updated_at" to I, "deleted" to I),
            listOf("id"), "updated_at", MergeRule.LWW,
        )
        val wordListEntry = TableSpec(
            "word_list_entry",
            listOf(
                "list_id" to T, "ref" to T, "text" to T, "reading" to T, "gloss" to T, "added_at" to I,
                "updated_at" to I, "deleted" to I,
            ),
            listOf("list_id", "ref"), "updated_at", MergeRule.LWW,
        )

        /** Card rows don't sync; only the user-set suspended flag does, as its own change type. */
        const val CARD_FLAGS = "card_flags"

        val all = listOf(item, itemRelation, review, note, setting, wordList, wordListEntry).associateBy { it.name }
    }
}
