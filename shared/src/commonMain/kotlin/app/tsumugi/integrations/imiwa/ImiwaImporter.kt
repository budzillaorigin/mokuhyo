package app.tsumugi.integrations.imiwa

import app.tsumugi.db.TsumugiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** One word from an imiwa? export (or any word/reading/meaning list). */
data class ImiwaWord(val text: String, val reading: String, val meaning: String)

data class ListImportResult(val listId: String, val imported: Int, val resolved: Int, val skipped: Int)

/**
 * imiwa? word-list exports → Tsumugi word lists (BRIEF §5.3, §9.5).
 *
 * imiwa exports are plain text: tab-separated or CSV (quoted fields), one word per line, columns word, reading,
 * meaning(s). Parsing is tolerant: UTF-8 BOM, optional header row (recognised by column names), Windows line
 * endings, semicolon separators, and extra columns (joined into the meaning) are all accepted.
 */
object ImiwaImporter {

    fun parse(text: String): List<ImiwaWord> {
        val body = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lines = body.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val delimiter = detectDelimiter(lines.take(20))
        var rows = lines.map { splitRow(it, delimiter) }

        // Column order from a header row, if there is one.
        var wordCol = 0
        var readingCol = 1
        var meaningCols: List<Int>? = null
        val header = rows.first().map { it.trim().lowercase() }
        val isHeader = header.any { it in WORD_HEADERS || it in READING_HEADERS || it in MEANING_HEADERS }
        if (isHeader) {
            wordCol = header.indexOfFirst { it in WORD_HEADERS }.takeIf { it >= 0 } ?: 0
            readingCol = header.indexOfFirst { it in READING_HEADERS }.takeIf { it >= 0 } ?: -1
            meaningCols = header.indices.filter { header[it] in MEANING_HEADERS }.ifEmpty { null }
            rows = rows.drop(1)
        }

        return rows.mapNotNull { cols ->
            val word = cols.getOrNull(wordCol)?.trim().orEmpty()
            if (word.isEmpty()) return@mapNotNull null
            val reading = if (readingCol >= 0) cols.getOrNull(readingCol)?.trim().orEmpty() else ""
            val meaningIdx = meaningCols ?: cols.indices.filter { it != wordCol && it != readingCol }
            val meaning = meaningIdx.mapNotNull { cols.getOrNull(it)?.trim() }.filter { it.isNotEmpty() }.joinToString("; ")
            ImiwaWord(word, reading, meaning)
        }
    }

    /**
     * Creates a word list named [listName] with [words]. [resolve] maps a word to a dictionary ref
     * (e.g. "jmdict:1358280") when it can be found; unresolved words are kept as text refs.
     */
    suspend fun importToList(
        db: TsumugiDatabase,
        listName: String,
        words: List<ImiwaWord>,
        clock: Clock = Clock.System,
        resolve: (suspend (ImiwaWord) -> String?)? = null,
    ): ListImportResult {
        val refs = words.map { w -> w to (resolve?.invoke(w) ?: textRef(w)) }
        return withContext(Dispatchers.IO) {
            val now = clock.now().toEpochMilliseconds()
            val listId = Uuid.random().toString()
            val q = db.userQueries
            var imported = 0
            var resolved = 0
            var skipped = 0
            db.transaction {
                q.insertList(listId, listName, now, now)
                val seen = HashSet<String>()
                for ((w, ref) in refs) {
                    if (!seen.add(ref)) {
                        skipped++
                        continue
                    }
                    q.putListEntry(listId, ref, w.text, w.reading, w.meaning, now)
                    imported++
                    if (!ref.startsWith(TEXT_REF)) resolved++
                }
            }
            ListImportResult(listId, imported, resolved, skipped)
        }
    }

    /** CSV (RFC 4180, header `word,reading,meaning`) of a word list, e.g. for imiwa or a spreadsheet. */
    suspend fun exportCsv(db: TsumugiDatabase, listId: String): String = withContext(Dispatchers.IO) {
        buildString {
            append("word,reading,meaning\r\n")
            for (e in db.userQueries.listEntries(listId).executeAsList()) {
                append(listOf(e.text, e.reading, e.gloss).joinToString(",") { csvField(it) }).append("\r\n")
            }
        }
    }

    fun textRef(w: ImiwaWord) = "$TEXT_REF${w.text}|${w.reading}"

    private const val TEXT_REF = "text:"
    private val WORD_HEADERS = setOf("word", "kanji", "expression", "japanese", "term", "単語", "見出し")
    private val READING_HEADERS = setOf("reading", "kana", "yomi", "furigana", "読み", "よみ")
    private val MEANING_HEADERS = setOf("meaning", "meanings", "english", "definition", "translation", "意味")

    private fun detectDelimiter(lines: List<String>): Char {
        val candidates = listOf('\t', ',', ';')
        return candidates.maxBy { d -> lines.sumOf { line -> splitRow(line, d).size - 1 } }
            .takeIf { d -> lines.any { splitRow(it, d).size > 1 } } ?: '\t'
    }

    /** Splits one delimited line, honouring double-quoted fields with "" escapes. */
    internal fun splitRow(line: String, delimiter: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' && (quoted || sb.isBlank()) -> { quoted = !quoted; if (!quoted) Unit else sb.clear() }
                c == delimiter && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun csvField(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
