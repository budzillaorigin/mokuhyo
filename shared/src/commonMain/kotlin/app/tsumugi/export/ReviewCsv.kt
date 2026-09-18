package app.tsumugi.export

import app.tsumugi.db.TsumugiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.time.Instant

/** One review log row as exported. [rating] 0 is a lesson introduction, 1–4 are Again/Hard/Good/Easy. */
data class ReviewCsvRow(
    val reviewId: String,
    val ts: Long,
    val cardId: String,
    val itemId: String,
    val itemKind: String,
    val direction: String,
    val itemText: String,
    val rating: Long,
    val correct: Long?,
    val elapsedMs: Long,
    val answer: String?,
    val deviceId: String,
    val source: String,
)

/**
 * CSV export of the whole review log (BRIEF_V2 G-10, DECISIONS D-115): every live review, oldest first;
 * tombstoned (undone) reviews are left out, as everywhere else the log is read. UTF-8 with a BOM so spreadsheet
 * apps open the Japanese correctly; RFC 4180 quoting.
 */
class ReviewCsvExporter(private val db: TsumugiDatabase) {

    @Throws(Exception::class)
    suspend fun rows(): List<ReviewCsvRow> = withContext(Dispatchers.IO) {
        val reviews = db.srsQueries.reviewsSince(Long.MIN_VALUE).executeAsList()
        val texts = HashMap<String, String>()
        reviews.map { it.item_id }.distinct().chunked(CHUNK).forEach { ids ->
            db.srsQueries.itemsByIds(ids).executeAsList().forEach { texts[it.id] = it.primary_text }
        }
        reviews.map {
            ReviewCsvRow(
                it.id, it.ts, it.card_id, it.item_id, it.item_kind, it.card_direction, texts[it.item_id].orEmpty(), it.rating,
                it.correct, it.elapsed_ms, it.answer_text, it.device_id, it.source,
            )
        }
    }

    /** The CSV text of [rows]. */
    fun csv(rows: List<ReviewCsvRow>): String = buildString {
        append(BOM)
        append(HEADER.joinToString(",")).append("\r\n")
        for (r in rows) {
            append(
                listOf(
                    r.reviewId, Instant.fromEpochMilliseconds(r.ts).toString(), r.ts.toString(), r.cardId, r.itemId, r.itemKind,
                    r.direction, r.itemText, r.rating.toString(), r.correct?.toString().orEmpty(), r.elapsedMs.toString(),
                    r.answer.orEmpty(), r.deviceId, r.source,
                ).joinToString(",") { field(it) },
            ).append("\r\n")
        }
    }

    /** Writes the CSV to [outPath]; returns the number of reviews. Runs on [Dispatchers.IO] (rule 15). */
    @Throws(Exception::class)
    suspend fun export(fs: FileSystem, outPath: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        val rows = rows()
        withContext(Dispatchers.IO) {
            fs.write(outPath.toPath()) {
                writeUtf8(BOM)
                writeUtf8(HEADER.joinToString(",") + "\r\n")
                rows.chunked(PROGRESS_EVERY).forEachIndexed { i, chunk ->
                    writeUtf8(csv(chunk).removePrefix(BOM).substringAfter("\r\n"))
                    onProgress(minOf((i + 1) * PROGRESS_EVERY, rows.size), rows.size)
                }
            }
        }
        onProgress(rows.size, rows.size)
        return rows.size
    }

    companion object {
        val HEADER = listOf(
            "review_id", "timestamp_utc", "epoch_ms", "card_id", "item_id", "item_kind", "direction", "item_text", "rating",
            "correct", "elapsed_ms", "answer", "device_id", "source",
        )
        private const val BOM = "﻿"
        private const val CHUNK = 500
        private const val PROGRESS_EVERY = 2_000

        fun field(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
