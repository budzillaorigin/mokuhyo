package app.tsumugi.integrations.bunpro

import app.tsumugi.domain.CardDirection
import app.tsumugi.grammar.GrammarPoint
import app.tsumugi.grammar.GrammarService
import app.tsumugi.integrations.wanikani.WaniKaniStageMapping
import app.tsumugi.jp.Kana
import app.tsumugi.srs.ImportedReview
import app.tsumugi.srs.SrsRepository
import kotlin.time.Clock

/** One row of a Bunpro export: the grammar point's title and its SRS level (0 = not started, 12 = burned). */
data class BunproRow(val title: String, val srsLevel: Int)

data class BunproImportResult(val rows: Int, val matched: Int, val unmatched: List<String>, val reviewsSeeded: Int)

/** Title matching shared by the grammar pack builder (tools/packs/build_grammar.py normalize_title). */
object GrammarTitles {
    fun normalize(title: String): String {
        val noBrackets = title.replace(Regex("[（(][^）)]*[）)]"), "")
        val compact = noBrackets.filterNot { it in "〜～~・/／、," || it.isWhitespace() }
        return Kana.toHiragana(compact).lowercase()
    }
}

/**
 * Bunpro progress import (BRIEF §9.2). Bunpro's public API is limited and changes, so this takes its CSV/TSV
 * export: any file with a grammar-title column and an SRS-level column. Titles are matched to our grammar points
 * by normalized title or the pack's alias table; SRS levels seed a plausible review history (same approach as
 * the WaniKani import). Bunpro's explanations are never imported — only which points you know and how well.
 */
class BunproImporter(
    private val grammar: GrammarService,
    private val srs: SrsRepository,
    private val clock: Clock = Clock.System,
) {
    @Throws(Exception::class)
    suspend fun import(text: String): BunproImportResult {
        val rows = parse(text)
        val index = grammar.titleIndex()
        val matched = ArrayList<Pair<BunproRow, GrammarPoint>>()
        val unmatched = ArrayList<String>()
        for (row in rows) {
            val point = index[GrammarTitles.normalize(row.title)]
            if (point == null) unmatched += row.title else matched += row to point
        }
        val started = matched.filter { it.first.srsLevel > 0 }
        grammar.learn(started.map { it.second }.distinctBy { it.id })
        val now = clock.now()
        val reviews = started.flatMap { (row, point) ->
            val cardId = SrsRepository.cardId(point.itemId, CardDirection.CLOZE)
            WaniKaniStageMapping.syntheticReviews(wkStage(row.srsLevel), now, now)
                .mapIndexed { i, (at, rating) -> ImportedReview(cardId, at, rating, "bp:${point.id}:$i", "bunpro") }
        }
        srs.importReviews(reviews)
        return BunproImportResult(rows.size, matched.size, unmatched, reviews.size)
    }

    companion object {
        /** Bunpro's 12 SRS levels onto WaniKani's 9 stages (both end in "burned"). */
        fun wkStage(bunproLevel: Int): Int = when {
            bunproLevel <= 0 -> 0
            bunproLevel <= 4 -> bunproLevel
            bunproLevel <= 6 -> 5 + (bunproLevel - 5)
            bunproLevel <= 8 -> 7
            bunproLevel <= 10 -> 8
            else -> 9
        }

        /** Tolerant CSV/TSV parsing: header row with a title-like and a level-like column; quotes; BOM. */
        fun parse(text: String): List<BunproRow> {
            val lines = text.removePrefix("﻿").lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) return emptyList()
            val sep = if (lines.first().count { it == '\t' } > lines.first().count { it == ',' }) '\t' else ','
            val header = split(lines.first(), sep).map { it.lowercase() }
            val titleCol = header.indexOfFirst { it.contains("grammar") || it.contains("title") || it == "point" || it == "name" }
            val levelCol = header.indexOfFirst { it.contains("srs") || it.contains("level") || it.contains("stage") }
            if (titleCol < 0) return emptyList()
            return lines.drop(1).mapNotNull { line ->
                val cells = split(line, sep)
                val title = cells.getOrNull(titleCol)?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                val level = cells.getOrNull(levelCol)?.trim()?.let { it.toIntOrNull() ?: levelWord(it) } ?: 1
                BunproRow(title, level)
            }
        }

        private fun levelWord(value: String): Int = when {
            value.contains("burn", ignoreCase = true) -> 12
            value.contains("expert", ignoreCase = true) -> 9
            value.contains("seasoned", ignoreCase = true) -> 7
            value.contains("adept", ignoreCase = true) -> 5
            value.contains("beginner", ignoreCase = true) -> 2
            else -> 1
        }

        private fun split(line: String, sep: Char): List<String> {
            val out = ArrayList<String>()
            val cell = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { cell.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    c == sep && !quoted -> { out += cell.toString(); cell.clear() }
                    else -> cell.append(c)
                }
                i++
            }
            out += cell.toString()
            return out
        }
    }
}
