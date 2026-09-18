package app.tsumugi.dictionary

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.dictionary.db.DictionaryDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Runs against the real content/packs/dictionary.sqlite when it has been built (tools/packs/build_all.py);
 * skipped otherwise so CI without the pack stays green. Checks real-data behaviour and the lookup budget.
 */
class RealPackSmokeTest {

    private val pack = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "content/packs/dictionary.sqlite") }
        .firstOrNull { it.exists() }

    private fun repo(): DictionaryRepository? = pack?.let {
        DictionaryRepository(DictionaryDatabase(JdbcSqliteDriver("jdbc:sqlite:${it.path}", Properties().apply { put("open_mode", "1") })))
    }

    @Test
    fun realLookups() = runTest {
        val repo = repo() ?: return@runTest println("RealPackSmokeTest skipped: no built pack")

        assertEquals("食べる", repo.search("食べさせられなかった").hits.first().entry.headword)
        assertEquals("猫", repo.search("cat").hits.first().entry.headword)
        assertTrue(repo.search("kanji").hits.take(3).any { it.entry.headword == "漢字" })
        val sentence = repo.search("私は毎日学校に行きます")
        assertEquals(SearchMode.SENTENCE, sentence.mode)
        assertTrue(sentence.tokens.any { it.dictionaryForm == "行く" })

        val detail = repo.entry(repo.search("食べる").hits.first().entry.id)!!
        assertTrue(detail.pitch.isNotEmpty() && detail.furigana.isNotEmpty() && detail.sentences.isNotEmpty())
        assertTrue(repo.kanji("語")!!.strokes.size == 14)
        assertTrue(repo.kanjiByRadicals(setOf("言", "口")).kanji.any { it.literal == "語" })

        // Warm up, then check the per-lookup budget. BRIEF §5.2's 5 ms is enforced on the iOS simulator
        // (DictionaryTests.lookupIsFast); here JDBC overhead and parallel builds make 10 ms the stable ceiling.
        val words = listOf("食べる", "たべる", "学校", "がっこう", "行きました", "猫", "漢字", "見られない", "ねこ", "東京")
        repeat(3) { words.forEach { repo.search(it) } }
        val elapsed = measureTime { repeat(10) { words.forEach { repo.search(it) } } }
        val perLookup = elapsed / 100
        println("RealPackSmokeTest: ${perLookup.inWholeMicroseconds} µs per lookup")
        assertTrue(perLookup.inWholeMilliseconds < 10, "lookup took $perLookup")
    }
}
