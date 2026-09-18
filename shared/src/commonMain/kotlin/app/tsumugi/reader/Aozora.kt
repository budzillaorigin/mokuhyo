package app.tsumugi.reader

import app.tsumugi.integrations.anki.Zip
import app.tsumugi.platform.normalizeNfc

/** One public-domain work from the Aozora Bunko catalogue. */
data class AozoraWork(val id: String, val title: String, val author: String, val textUrl: String, val kana: String?)

/**
 * Aozora Bunko (public-domain literature, aozora.gr.jp). Parses the ruby-annotated text format and the official
 * catalogue CSV (list_person_all_extended_utf8.csv), and downloads works on the user's request.
 *
 * Text format handled: title/author lines, the 【テキスト中に現れる記号について】 notes block between dashed
 * lines (removed), ruby as 漢字《かんじ》 or ｜base《reading》 (kept as [RubyHint]s, removed from the text),
 * editor annotations ［＃…］ (removed), and the 底本 colophon at the end (removed).
 */
object AozoraImporter {

    /** Parses an Aozora text file (already decoded; files are Shift_JIS inside the zip). */
    fun parseText(raw: String, sourceUrl: String? = null): ImportedText {
        val lines = normalizeNfc(raw).removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').lines()
        val title = lines.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val author = lines.drop(1).firstOrNull { it.isNotBlank() }?.trim()

        // Body starts after the notes block (two dashed lines) if present, else after title/author lines.
        val dashed = lines.indices.filter { lines[it].trim().length >= 10 && lines[it].trim().all { c -> c == '-' } }
        var start = if (dashed.size >= 2) dashed[1] + 1 else lines.indexOfFirst { it.isNotBlank() } + 2
        start = start.coerceIn(0, lines.size)
        var end = lines.indexOfFirst { it.startsWith("底本：") }.let { if (it < 0) lines.size else it }
        if (end < start) end = lines.size
        val bodyLines = lines.subList(start, end)

        val ruby = ArrayList<RubyHint>()
        val body = StringBuilder()
        for (line in bodyLines) {
            val cleaned = line.replace(ANNOTATION, "")
            appendWithRuby(cleaned, body, ruby)
            body.append('\n')
        }
        // Collapse the blank lines Aozora uses between paragraphs; keep one paragraph per line.
        val text = body.toString()
        val normalized = normalizeParagraphs(text, ruby)
        return ImportedText(title, normalized.first, SourceKind.AOZORA, sourceUrl, author, normalized.second)
    }

    /** Appends [line] to [out] with ruby markup removed, recording each reading's position. */
    private fun appendWithRuby(line: String, out: StringBuilder, ruby: MutableList<RubyHint>) {
        var i = 0
        var explicitBaseStart = -1
        while (i < line.length) {
            val c = line[i]
            when {
                c == '｜' -> { explicitBaseStart = out.length; i++ }
                c == '《' -> {
                    val close = line.indexOf('》', i)
                    if (close < 0) { out.append(c); i++; continue }
                    val reading = line.substring(i + 1, close)
                    val baseStart = if (explicitBaseStart >= 0) explicitBaseStart else implicitBaseStart(out)
                    if (baseStart in 0 until out.length) ruby += RubyHint(baseStart, out.substring(baseStart), reading)
                    explicitBaseStart = -1
                    i = close + 1
                }
                else -> { out.append(c); i++ }
            }
        }
    }

    /** Without ｜, ruby applies to the run of kanji (incl. 々〆ヶ) just before 《. */
    private fun implicitBaseStart(out: StringBuilder): Int {
        var j = out.length
        while (j > 0 && isRubyBaseChar(out[j - 1])) j--
        return if (j == out.length) -1 else j
    }

    private fun isRubyBaseChar(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々' || c == '〆' || c == 'ヶ' || c == '〇'

    private fun normalizeParagraphs(text: String, ruby: List<RubyHint>): Pair<String, List<RubyHint>> {
        // Map old offsets to new ones while dropping blank lines and trailing spaces.
        val out = StringBuilder()
        val map = IntArray(text.length + 1) { -1 }
        var offset = 0
        for (content in text.split('\n')) {
            val trimmed = content.trimEnd()
            if (trimmed.isNotBlank()) {
                if (out.isNotEmpty()) out.append('\n')
                for (k in trimmed.indices) map[offset + k] = out.length + k
                out.append(trimmed)
            }
            offset += content.length + 1
        }
        val moved = ruby.mapNotNull { h -> map.getOrNull(h.start)?.takeIf { it >= 0 }?.let { h.copy(start = it) } }
        return out.toString() to moved
    }

    private val ANNOTATION = Regex("［＃[^］]*］")

    // --- Catalogue ------------------------------------------------------------------------------------------

    /**
     * Works from the official catalogue CSV (UTF-8 edition). Only works whose 作品著作権フラグ is なし (public
     * domain) and that have a text file are returned.
     */
    fun parseCatalogue(csv: String): List<AozoraWork> {
        val rows = Csv.parse(csv.removePrefix("﻿"))
        val header = rows.firstOrNull() ?: return emptyList()
        fun col(name: String) = header.indexOf(name)
        val id = col("作品ID"); val title = col("作品名"); val family = col("姓"); val given = col("名")
        val url = col("テキストファイルURL"); val copyright = col("作品著作権フラグ"); val kana = col("作品名読み")
        if (id < 0 || title < 0 || url < 0) return emptyList()
        return rows.drop(1).mapNotNull { r ->
            val textUrl = r.getOrNull(url).orEmpty()
            if (textUrl.isBlank() || (copyright >= 0 && r.getOrNull(copyright) != "なし")) return@mapNotNull null
            AozoraWork(r[id], r[title], listOfNotNull(r.getOrNull(family), r.getOrNull(given)).joinToString(""), textUrl, r.getOrNull(kana))
        }.distinctBy { it.id }
    }

    /** The text file of a work: Aozora zips hold one Shift_JIS .txt. */
    fun textFromZip(zip: ByteArray, sourceUrl: String? = null): ImportedText {
        val entries = Zip.read(zip)
        val txt = entries.entries.firstOrNull { it.key.endsWith(".txt", ignoreCase = true) }?.value
            ?: throw ImportException("No text file in the Aozora archive")
        return parseText(decodeText(txt, "Shift_JIS"), sourceUrl)
    }
}

/** Downloads Aozora works on the user's request. */
class AozoraService(private val web: UrlImporter, private val repo: ReaderRepository) {
    @Throws(Exception::class)
    suspend fun catalogue(zipUrl: String = CATALOGUE_URL): List<AozoraWork> {
        val (bytes, _) = web.fetch(zipUrl)
        val csv = Zip.read(bytes).entries.firstOrNull { it.key.endsWith(".csv") }?.value
            ?: throw ImportException("No CSV in the Aozora catalogue archive")
        return AozoraImporter.parseCatalogue(csv.decodeToString())
    }

    @Throws(Exception::class)
    suspend fun import(work: AozoraWork): String {
        val (bytes, _) = web.fetch(work.textUrl)
        val text = if (work.textUrl.endsWith(".zip")) AozoraImporter.textFromZip(bytes, work.textUrl)
        else AozoraImporter.parseText(decodeText(bytes, "Shift_JIS"), work.textUrl)
        return repo.save(text.copy(title = work.title, author = work.author.ifEmpty { text.author }))
    }

    companion object {
        const val CATALOGUE_URL = "https://www.aozora.gr.jp/index_pages/list_person_all_extended_utf8.zip"
    }
}

/** RFC 4180-ish CSV: quoted fields, doubled quotes, newlines inside quotes. */
internal object Csv {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> cell.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> { row += cell.toString(); cell.clear() }
                    '\r' -> Unit
                    '\n' -> { row += cell.toString(); cell.clear(); rows += row; row = ArrayList() }
                    else -> cell.append(c)
                }
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }
}
