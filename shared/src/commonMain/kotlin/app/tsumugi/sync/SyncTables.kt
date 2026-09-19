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
    /** Immutable facts merged by set union (relations, exam attempts, path unlocks). */
    UNION,
    /**
     * Set union plus a one-way tombstone column (reviews, CLAUDE.md rule 12): a row tombstoned anywhere ends up
     * tombstoned everywhere, and the earliest tombstone time wins so every device stores the same value.
     */
    UNION_TOMBSTONE,
    /** Last writer wins by (updatedAt, deviceId). */
    LWW,
    /**
     * The highest [TableSpec.rank] tuple wins, then the higher deviceId (path progress, CLAUDE.md rule 11). A later
     * write of a lower level loses; only raising a more significant rank column (the reset generation) lowers it.
     */
    MAX,
}

/** How one synced table maps to wire rows (docs/SYNC_PROTOCOL.md "Synced tables"). */
internal class TableSpec(
    val name: String,
    val columns: List<Pair<String, ColumnType>>,
    val keys: List<String>,
    val updatedAt: String?,
    val merge: MergeRule,
    /** [MergeRule.UNION_TOMBSTONE]: the nullable tombstone-time column. */
    val tombstone: String? = null,
    /** [MergeRule.MAX]: integer columns compared lexicographically, most significant first. */
    val rank: List<String> = emptyList(),
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

    /** Every row of the table, in primary-key order (JSON backup, D-116). */
    fun readAll(driver: SqlDriver): List<JsonObject> =
        driver.executeQuery(null, "SELECT $columnList FROM $name ORDER BY ${keys.joinToString(", ")}", { cursor ->
            val out = ArrayList<JsonObject>()
            while (cursor.next().value) out += rowOf(cursor)
            QueryResult.Value(out)
        }, 0).value

    /** The row's key as the wire carries it (composite keys joined with [KEY_SEPARATOR]). */
    fun keyOf(row: JsonObject): String = keys.joinToString(KEY_SEPARATOR) { (row[it] as? JsonPrimitive)?.content.orEmpty() }

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

    /** The row's version on the wire: its tombstone time once tombstoned (so the update is a distinct change), else [updatedAt]. */
    fun updatedAtOf(row: JsonObject): Long =
        tombstoneOf(row) ?: updatedAt?.let { (row[it] as? JsonPrimitive)?.longOrNull } ?: 0L

    fun tombstoneOf(row: JsonObject): Long? = tombstone?.let { (row[it] as? JsonPrimitive)?.longOrNull }

    fun rankOf(row: JsonObject): List<Long> = rank.map { (row[it] as? JsonPrimitive)?.longOrNull ?: 0L }

    /** Tombstones an existing row at [at] unless it already carries an earlier tombstone. */
    fun applyTombstone(driver: SqlDriver, key: String, at: Long) {
        val column = tombstone ?: return
        val parts = splitKey(key)
        driver.execute(null, "UPDATE $name SET $column = ? WHERE $keyWhere AND ($column IS NULL OR $column > ?)", keys.size + 2) {
            bindLong(0, at)
            parts.forEachIndexed { i, v -> bindString(i + 1, v) }
            bindLong(parts.size + 1, at)
        }
    }

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
                "correct" to I, "device_id" to T, "source" to T, "deleted_at" to I,
            ),
            listOf("id"), "ts", MergeRule.UNION_TOMBSTONE, tombstone = "deleted_at",
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

        val examAttempt = TableSpec(
            "exam_attempt",
            listOf(
                "id" to T, "exam" to T, "level" to T, "mode" to T, "started_at" to I, "submitted_at" to I, "answers" to T,
                "scoring" to T, "summary" to T, "device_id" to T,
            ),
            listOf("id"), "submitted_at", MergeRule.UNION,
        )

        val pathProgress = TableSpec(
            "path_progress",
            listOf("track" to T, "generation" to I, "passed_level" to I, "passed_at" to I, "updated_at" to I),
            listOf("track"), "updated_at", MergeRule.MAX, rank = listOf("generation", "passed_level", "updated_at"),
        )
        val pathUnlock = TableSpec(
            "path_unlock", listOf("item_id" to T, "source" to T, "unlocked_at" to I),
            listOf("item_id"), "unlocked_at", MergeRule.UNION,
        )

        /** Finished conversations (free talk, role-plays): written once, immutable (DECISIONS D-101). */
        val conversation = TableSpec(
            "conversation",
            listOf(
                "id" to T, "mode" to T, "scenario_id" to T, "started_at" to I, "ended_at" to I, "level" to T,
                "turns" to T, "level_estimate" to T, "errors" to T, "engine" to T, "device_id" to T,
            ),
            listOf("id"), "ended_at", MergeRule.UNION,
        )
        /** Today blocks finished per local day (weekly challenges count them; D-100). */
        val todayBlockDone = TableSpec(
            "today_block_done", listOf("day" to T, "block" to T, "completed_at" to I),
            listOf("day", "block"), "completed_at", MergeRule.UNION,
        )
        /** Streak freeze days (D-106). */
        val streakFreeze = TableSpec(
            "streak_freeze", listOf("day" to T, "reason" to T, "created_at" to I),
            listOf("day"), "created_at", MergeRule.UNION,
        )

        /** Media decks (BRIEF_V2 §6.1, D-150): LWW with a `deleted` tombstone flag. */
        val mediaDeck = TableSpec(
            "media_deck",
            listOf(
                "id" to T, "title" to T, "source_kind" to T, "source_ref" to T, "word_count" to I, "token_count" to I,
                "kanji" to T, "grammar" to T, "stats" to T, "created_at" to I, "updated_at" to I, "deleted" to I,
            ),
            listOf("id"), "updated_at", MergeRule.LWW,
        )
        val mediaDeckWord = TableSpec(
            "media_deck_word",
            listOf(
                "deck_id" to T, "entry_id" to I, "ord" to I, "text" to T, "reading" to T, "count" to I, "score" to ColumnType.REAL,
                "context" to T, "updated_at" to I, "deleted" to I,
            ),
            listOf("deck_id", "entry_id"), "updated_at", MergeRule.LWW,
        )
        /** Words marked known: rows are only ever added (a union of words); the known flag is LWW (D-151). */
        val knownWord = TableSpec(
            "known_word", listOf("entry_id" to I, "text" to T, "known" to I, "source" to T, "updated_at" to I),
            listOf("entry_id"), "updated_at", MergeRule.LWW,
        )

        /** Immersion log (BRIEF_V2 §6.11, D-167): written once; a delete is a one-way tombstone, like reviews. */
        val immersionSession = TableSpec(
            "immersion_session",
            listOf(
                "id" to T, "day" to T, "source" to T, "mode" to T, "started_at" to I, "duration_s" to I, "ref" to T,
                "title" to T, "device_id" to T, "created_at" to I, "deleted_at" to I,
            ),
            listOf("id"), "created_at", MergeRule.UNION_TOMBSTONE, tombstone = "deleted_at",
        )
        /** Reader annotations (BRIEF_V2 §6.4, D-164): per-annotation rows, LWW, `deleted` tombstone. */
        val readerAnnotation = TableSpec(
            "reader_annotation",
            listOf(
                "id" to T, "doc_key" to T, "kind" to T, "start_offset" to I, "end_offset" to I, "quote" to T, "note" to T,
                "color" to T, "grammar_id" to T, "created_at" to I, "updated_at" to I, "deleted" to I,
            ),
            listOf("id"), "updated_at", MergeRule.LWW,
        )

        /** Grammar mastery checkboxes (BRIEF_V2 §6.6, D-231): rows only ever added; the flag is LWW, like known_word. */
        val grammarMastery = TableSpec(
            "grammar_mastery", listOf("point_id" to T, "mastered" to I, "updated_at" to I),
            listOf("point_id"), "updated_at", MergeRule.LWW,
        )

        /** Translation attempts (BRIEF_V2 §6.12, D-274): written once; a delete is a one-way tombstone, like reviews. */
        val translationAttempt = TableSpec(
            "translation_attempt",
            listOf(
                "id" to T, "passage_id" to T, "direction" to T, "genre" to T, "level" to T, "mode" to T, "source_text" to T,
                "attempt_text" to T, "duration_ms" to I, "time_limit_ms" to I, "grader" to T, "accuracy" to I, "completeness" to I,
                "register" to I, "naturalness" to I, "score" to I, "grade" to T, "engine" to T, "device_id" to T,
                "created_at" to I, "deleted_at" to I,
            ),
            listOf("id"), "created_at", MergeRule.UNION_TOMBSTONE, tombstone = "deleted_at",
        )
        /** Writing-studio drafts (BRIEF_V2 §6.13, D-275): LWW with a `deleted` tombstone flag. */
        val writingDraft = TableSpec(
            "writing_draft",
            listOf(
                "id" to T, "title" to T, "body" to T, "task_ref" to T, "task_prompt" to T, "target_register" to T,
                "created_at" to I, "updated_at" to I, "deleted" to I,
            ),
            listOf("id"), "updated_at", MergeRule.LWW,
        )
        /** Solo reading-circle sessions (BRIEF_V2 §6.14, D-278): LWW with a `deleted` tombstone flag. */
        val circleSession = TableSpec(
            "circle_session",
            listOf(
                "id" to T, "text_id" to T, "title" to T, "sentence_count" to I, "position" to I, "entries" to T,
                "started_at" to I, "finished_at" to I, "updated_at" to I, "deleted" to I,
            ),
            listOf("id"), "updated_at", MergeRule.LWW,
        )
        /** Pitch-accent test answers (BRIEF_V2 §6.7, D-284): written once, merged by union like exam attempts. */
        val pitchTestResult = TableSpec(
            "pitch_test_result",
            listOf(
                "id" to T, "item_id" to T, "mode" to T, "expected" to T, "answer" to T, "correct" to I, "pattern" to T,
                "mora_count" to I, "level" to I, "response_ms" to I, "answered_at" to I, "device_id" to T,
            ),
            listOf("id"), "answered_at", MergeRule.UNION,
        )
        /** Mini-game rounds (BRIEF_V2 §6.9, D-286): written once, merged by union; the weekly challenge sums them. */
        val gameScore = TableSpec(
            "game_score",
            listOf(
                "id" to T, "game" to T, "score" to I, "correct" to I, "total" to I, "best_streak" to I, "duration_ms" to I,
                "day" to T, "played_at" to I, "device_id" to T,
            ),
            listOf("id"), "played_at", MergeRule.UNION,
        )

        /** Card rows don't sync; only the user-set suspended flag does, as its own change type. */
        const val CARD_FLAGS = "card_flags"

        val all = listOf(
            item, itemRelation, review, note, setting, wordList, wordListEntry, examAttempt, pathProgress, pathUnlock,
            conversation, todayBlockDone, streakFreeze, mediaDeck, mediaDeckWord, knownWord, immersionSession, readerAnnotation,
            grammarMastery, pitchTestResult, gameScore, translationAttempt, writingDraft, circleSession,
        ).associateBy { it.name }
    }
}
