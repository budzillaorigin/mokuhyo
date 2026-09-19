package app.tsumugi.coverage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.jp.tokenizer.LatticeTokenizer
import app.tsumugi.reader.ReaderAnalyzer
import app.tsumugi.tokenizer.db.TokenizerDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Calibrates the §6.4 difficulty score (docs/CONTENT_PACKS.md "Difficulty score") on the DLPT reading bank
 * (tools/items/bank/dlpt_reading.json: 10 passages at each ILR level 0+ … 3) with the real dictionary and
 * tokenizer packs. Skipped when the packs haven't been built (tools/packs/build_all.py).
 */
class DifficultyCalibrationTest {
    private fun find(path: String) = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.exists() }

    private fun readOnly(file: File) = JdbcSqliteDriver("jdbc:sqlite:${file.path}", Properties().apply { put("open_mode", "1") })

    private val levels = listOf("0+", "1", "1+", "2", "2+", "3")

    @Test
    fun dlptPassagesScoreInIlrOrder() = runTest {
        val dictPack = find("content/packs/dictionary.sqlite")
        val tokPack = find("content/packs/tokenizer.sqlite")
        val bank = find("tools/items/bank/dlpt_reading.json")
        if (dictPack == null || tokPack == null || bank == null) return@runTest println("DifficultyCalibrationTest skipped: packs not built")
        val dictionary = DictionaryRepository(DictionaryDatabase(readOnly(dictPack)))
        val analyzer = ReaderAnalyzer(LatticeTokenizer(TokenizerDatabase(readOnly(tokPack))), dictionary, { emptyMap() })
        val profiler = TextProfiler({ analyzer }, { dictionary.wordStats(it) })

        val passages = Json.parseToJsonElement(bank.readText()).jsonObject["passages"]!!.jsonArray.map { it.jsonObject }
        val scored = passages.map { p ->
            val level = p["level"]!!.jsonPrimitive.content
            val profile = profiler.profile(p["body"]!!.jsonPrimitive.content)!!
            levels.indexOf(level) to DifficultyScorer.score(profile)
        }
        val byLevel = scored.groupBy({ it.first }, { it.second })
        for ((i, l) in levels.withIndex()) {
            val s = byLevel[i].orEmpty()
            println(
                "ILR $l: text ${s.map { it.textScore }.sorted()} mean ${"%.1f".format(s.map { it.textScore }.average())} · " +
                    "labels ${s.groupingBy { it.ilr }.eachCount().toSortedMap()} · " +
                    "V ${"%.2f".format(s.map { it.vocabulary }.average())} S ${"%.2f".format(s.map { it.sentenceLength }.average())} " +
                    "K ${"%.2f".format(s.map { it.kanjiDensity }.average())} A ${"%.2f".format(s.map { it.abstractness }.average())}",
            )
        }
        val means = levels.indices.map { i -> byLevel[i]!!.map { it.textScore }.average() }
        // Means rise level by level. 0+ (signs, labels) is short but kanji-heavy with specialized words, so it may sit
        // up to 2 points above level 1; every other step must rise.
        assertTrue(means[0] < means[1] + 2.0, "0+ scores about as low as 1: $means")
        assertTrue(means.drop(1).zipWithNext().all { (a, b) -> a < b }, "mean score rises with every ILR level from 1 up: $means")

        // Pairwise: a passage scores above one at least two ILR steps easier most of the time.
        var ordered = 0
        var pairs = 0
        for ((la, a) in scored) for ((lb, b) in scored) if (lb - la >= 2) {
            pairs++
            if (b.textScore > a.textScore) ordered++
        }
        val share = ordered.toDouble() / pairs
        println("pairs two+ levels apart in order: ${"%.3f".format(share)}")
        assertTrue(share >= 0.85, "only $share of pairs in order")

        // The label is within one ILR step of the bank's level for most passages.
        val close = scored.count { (level, s) -> kotlin.math.abs(levels.indexOf(s.ilr) - level) <= 1 }
        println("labels within one step: $close / ${scored.size}")
        assertTrue(close >= scored.size * 0.7, "labels within one step: $close / ${scored.size}")
    }

    @Test
    fun realPackHasTheCoreFrequencyList() = runTest {
        val dictPack = find("content/packs/dictionary.sqlite") ?: return@runTest println("skipped: no dictionary pack")
        val dictionary = DictionaryRepository(DictionaryDatabase(readOnly(dictPack)))
        val count = dictionary.frequencyWordCount()
        if (count == 0) return@runTest println("skipped: dictionary pack built before tools/packs/build_decks.py")
        assertEquals(10_000, count)
        val top = dictionary.summaries(dictionary.frequencyWords(0, 30).map { it.entryId }).map { it.headword }
        println("Core top 30: $top")
        assertTrue("私" in top && "する" in top, "the most frequent words lead the list: $top")
        assertTrue(top.none { it == "を" || it == "は" }, "particles are left out")
    }
}
