package app.tsumugi.jp.tokenizer

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.tokenizer.db.TokenizerDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * BRIEF §11 TokenizerParityTest: the pure-Kotlin analyzer against a reference segmentation of 1,000 Tatoeba
 * sentences produced by MeCab with the same mecab-ipadic dictionary (tools/packs/tokenizer_parity.py).
 * Runs when content/packs/tokenizer.sqlite has been built; skipped otherwise.
 */
class TokenizerParityTest {

    private fun find(relative: String): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, relative) }.firstOrNull { it.exists() }

    @Test
    fun matchesReferenceSegmentation() = runTest {
        val pack = find("content/packs/tokenizer.sqlite") ?: return@runTest println("TokenizerParityTest skipped: no built pack")
        val golden = find("shared/src/androidHostTest/resources/tokenizer/parity.tsv") ?: error("golden file missing")
        val analyzer = LatticeTokenizer(TokenizerDatabase(JdbcSqliteDriver("jdbc:sqlite:${pack.path}")))

        val cases = golden.readLines().filter { it.isNotBlank() }.map { line ->
            val fields = line.split('\t')
            fields.first() to fields.drop(1).map { it.split('') }
        }
        var sentencesExact = 0
        var tokensExpected = 0
        var tokensMatched = 0
        val mismatches = ArrayList<String>()
        analyzer.analyze(cases.first().first) // load tables before timing
        val elapsed = measureTime {
            for ((sentence, expected) in cases) {
                val actual = analyzer.analyze(sentence)
                val got = actual.map { listOf(it.surface, it.pos.firstOrNull().orEmpty(), it.baseForm) }
                if (got == expected) sentencesExact++ else if (mismatches.size < 15) {
                    mismatches += "$sentence\n  want ${expected.joinToString(" ") { it[0] + "/" + it[1] }}\n  got  ${got.joinToString(" ") { it[0] + "/" + it[1] }}"
                }
                tokensExpected += expected.size
                val gotSet = got.map { it[0] + "|" + it[1] }.groupingBy { it }.eachCount().toMutableMap()
                for (e in expected) {
                    val key = e[0] + "|" + e[1]
                    val n = gotSet[key] ?: 0
                    if (n > 0) { tokensMatched++; gotSet[key] = n - 1 }
                }
            }
        }
        val chars = cases.sumOf { it.first.length }
        val sentenceRate = sentencesExact.toDouble() / cases.size
        val tokenRate = tokensMatched.toDouble() / tokensExpected
        println("TokenizerParityTest: sentences exact ${"%.1f".format(sentenceRate * 100)}% ($sentencesExact/${cases.size}), " +
            "tokens ${"%.2f".format(tokenRate * 100)}%, speed ${(chars / elapsed.inWholeMilliseconds.coerceAtLeast(1) * 1000)} chars/s")
        mismatches.forEach(::println)
        assertTrue(sentenceRate >= MIN_SENTENCE_PARITY, "sentence parity $sentenceRate")
        assertTrue(tokenRate >= MIN_TOKEN_PARITY, "token parity $tokenRate")
    }

    private companion object {
        // Measured: 99.8% of sentences and 99.97% of tokens identical. The known differences are standalone
        // symbols (．, ℃, ～) that the reference MeCab build tags 記号 where mecab-ipadic's unk.def says 名詞.
        const val MIN_SENTENCE_PARITY = 0.99
        const val MIN_TOKEN_PARITY = 0.995
    }
}
