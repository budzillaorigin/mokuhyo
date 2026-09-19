package app.tsumugi.lyrics

import app.tsumugi.platform.normalizeNfc

/** A word-level time from enhanced LRC (`<mm:ss.xx>word`): [text] starts at [startMs]. */
data class LrcWord(val startMs: Long, val text: String)

/** One timed lyric line. [words] is non-empty only for enhanced (word-level) LRC and tiles [text]. */
data class LrcLine(val startMs: Long, val text: String, val words: List<LrcWord>)

data class LrcFile(
    val title: String?,
    val artist: String?,
    val album: String?,
    /** The `[offset:]` tag as written (already applied to every time below). */
    val offsetMs: Long,
    /** Sorted by time; a line with several stamps (a repeated chorus) appears once per stamp. */
    val lines: List<LrcLine>,
) {
    val hasWordTiming: Boolean get() = lines.any { it.words.isNotEmpty() }
}

/**
 * LRC parsing (BRIEF_V2 §6.3, DECISIONS D-163): simple LRC (`[mm:ss.xx]line`), several stamps on one line
 * (`[00:10.00][01:20.00]chorus`), the ID tags ti/ar/al/offset, and enhanced LRC with word times
 * (`[00:10.00]<00:10.00>今日<00:10.50>は`). Fractions of one or two digits are tenths/hundredths, three digits
 * milliseconds; `mm:ss:xx` (colon before the fraction) is accepted too. `[offset:+500]` shows lyrics 500 ms
 * earlier, as in the format's convention (times are shifted by −offset, clamped at 0). Text is NFC.
 */
object Lrc {
    private val lineStamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val idTag = Regex("""^\[([a-zA-Z#]+):(.*)]\s*$""")

    /** True when [text] has at least one timed line (so it isn't plain lyrics). */
    fun isLrc(text: String): Boolean = text.lineSequence().any { lineStamp.find(it.trim())?.range?.first == 0 }

    fun parse(text: String): LrcFile {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var offset = 0L
        val raw = ArrayList<Pair<Long, String>>()
        for (rawLine in text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val tag = idTag.find(line)
            if (tag != null && lineStamp.find(line)?.range?.first != 0) {
                val value = tag.groupValues[2].trim()
                when (tag.groupValues[1].lowercase()) {
                    "ti" -> title = value.ifEmpty { null }?.let(::normalizeNfc)
                    "ar" -> artist = value.ifEmpty { null }?.let(::normalizeNfc)
                    "al" -> album = value.ifEmpty { null }?.let(::normalizeNfc)
                    "offset" -> offset = value.removePrefix("+").toLongOrNull() ?: 0L
                }
                continue
            }
            // Leading stamps: [a][b]text
            var rest = line
            val stamps = ArrayList<Long>()
            while (true) {
                val m = lineStamp.find(rest) ?: break
                if (m.range.first != 0) break
                stamps += ms(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) continue
            for (s in stamps) raw += s to rest
        }
        val lines = raw.map { (start, body) -> line(start - offset, body, offset) }.sortedBy { it.startMs }
        return LrcFile(title, artist, album, offset, lines)
    }

    private fun line(start: Long, body: String, offset: Long): LrcLine {
        val stamps = wordStamp.findAll(body).toList()
        if (stamps.isEmpty()) return LrcLine(start.coerceAtLeast(0), normalizeNfc(body.trim()), emptyList())
        val words = ArrayList<LrcWord>()
        // Text before the first word stamp belongs to the line start.
        val lead = body.substring(0, stamps.first().range.first)
        if (lead.isNotBlank()) words += LrcWord(start.coerceAtLeast(0), normalizeNfc(lead))
        stamps.forEachIndexed { i, m ->
            val end = if (i + 1 < stamps.size) stamps[i + 1].range.first else body.length
            val text = body.substring(m.range.last + 1, end)
            if (text.isNotEmpty()) {
                val t = (ms(m.groupValues[1], m.groupValues[2], m.groupValues[3]) - offset).coerceAtLeast(0)
                words += LrcWord(t, normalizeNfc(text))
            }
        }
        // Trim the line's outer whitespace but keep the words tiling the text.
        val text = words.joinToString("") { it.text }
        val lead0 = text.length - text.trimStart().length
        val trimmed = text.trim()
        val tiled = if (lead0 == 0 && trimmed.length == text.length) {
            words
        } else {
            var at = 0
            words.mapNotNull { w ->
                val from = at
                at += w.text.length
                val s = maxOf(from, lead0)
                val e = minOf(at, lead0 + trimmed.length)
                if (e > s) LrcWord(w.startMs, text.substring(s, e)) else null
            }
        }
        return LrcLine(start.coerceAtLeast(0), trimmed, tiled.filter { it.text.isNotEmpty() })
    }

    private fun ms(min: String, sec: String, frac: String): Long {
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return (min.toLong() * 60 + sec.toLong()) * 1000 + f
    }

    /** Writes lines back as LRC (word times as enhanced LRC when present), for export (rule 6). */
    fun write(lines: List<LyricLine>, title: String? = null, artist: String? = null): String = buildString {
        title?.let { append("[ti:").append(it).append("]\n") }
        artist?.let { append("[ar:").append(it).append("]\n") }
        for (l in lines) {
            val start = l.startMs
            if (start != null) append(stamp(start, '[', ']'))
            if (l.words.isNotEmpty() && start != null) {
                var at = 0
                for (w in l.words) {
                    if (w.start > at) append(l.text, at, w.start)
                    append(stamp(w.startMs, '<', '>'))
                    append(l.text, w.start, w.end)
                    at = w.end
                }
                if (at < l.text.length) append(l.text, at, l.text.length)
            } else {
                append(l.text)
            }
            append('\n')
        }
    }

    fun stamp(ms: Long, open: Char, close: Char): String {
        val m = ms / 60_000
        val s = (ms / 1000) % 60
        val cs = (ms % 1000) / 10
        return "$open${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}.${cs.toString().padStart(2, '0')}$close"
    }
}
