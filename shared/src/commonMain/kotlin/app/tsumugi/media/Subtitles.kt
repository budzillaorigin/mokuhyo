package app.tsumugi.media

import app.tsumugi.platform.normalizeNfc

/** One subtitle cue; times in milliseconds. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/** A Japanese cue with the English cue that overlaps it most (dual subtitles, BRIEF §5.9). */
data class DualCue(val startMs: Long, val endMs: Long, val japanese: String, val english: String?)

/**
 * SRT and WebVTT parsing for the media player (user-supplied files; nothing is fetched). Tolerant of BOMs, CRLF,
 * cue numbers, VTT headers/NOTE/STYLE blocks and cue settings; strips simple markup (<i>, {\an8}, <c.x>, ruby
 * parentheses are kept as text).
 */
object Subtitles {
    private val timing = Regex("""(\d{1,2}:)?(\d{1,2}):(\d{2})[.,](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*}""")

    fun parse(text: String): List<Cue> {
        val blocks = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n\\s*\n"))
        val cues = mutableListOf<Cue>()
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timingIndex = lines.indexOfFirst { timing.containsMatchIn(it) }
            if (timingIndex < 0) continue
            val m = timing.find(lines[timingIndex]) ?: continue
            val g = m.groupValues
            val start = ms(g[1], g[2], g[3], g[4])
            val end = ms(g[5], g[6], g[7], g[8])
            val body = lines.drop(timingIndex + 1).joinToString("\n") { it.replace(tags, "").trim() }.trim()
            if (body.isNotEmpty() && end > start) cues += Cue(start, end, normalizeNfc(body.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")))
        }
        return cues.sortedBy { it.startMs }
    }

    /** The cue showing at [positionMs] (the latest-starting one if cues overlap), or null. */
    fun cueAt(cues: List<Cue>, positionMs: Long): Cue? {
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        var i = found
        while (i >= 0) {
            if (cues[i].endMs > positionMs) return cues[i]
            if (positionMs - cues[i].startMs > MAX_CUE_MS) break
            i--
        }
        return null
    }

    /** Pairs each Japanese cue with the English cue overlapping it most (none when nothing overlaps). */
    fun dual(japanese: List<Cue>, english: List<Cue>): List<DualCue> = japanese.map { ja ->
        val best = english
            .map { en -> en to (minOf(ja.endMs, en.endMs) - maxOf(ja.startMs, en.startMs)) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }?.first
        DualCue(ja.startMs, ja.endMs, ja.text, best?.text)
    }

    /** Index of the previous/next cue for "replay line" and "next line" controls. */
    fun indexAt(cues: List<Cue>, positionMs: Long): Int = cues.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

    private fun ms(h: String, m: String, s: String, frac: String): Long {
        val hours = h.removeSuffix(":").toLongOrNull() ?: 0
        val millis = frac.padEnd(3, '0').toLong()
        return ((hours * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + millis
    }

    private const val MAX_CUE_MS = 60_000L
}
