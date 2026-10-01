package app.mokuhyo.dictionary

import app.mokuhyo.lang.DictionaryPack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Built packs (`tools/packs/build_dictionary.py`) against known common words per language
 * (`jvmTest/resources/dictionary/known_words.json`) and the < 5 ms lookup budget (BRIEF §5.2). Languages whose pack
 * isn't built under `content/packs/<lang>/dictionary.sqlite` are skipped, so CI without packs stays green.
 */
class RealPacksTest {
    private val known: JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/dictionary/known_words.json")).readText(),
    ).jsonObject

    private fun packFile(language: String): File? {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "content/packs/$language/dictionary.sqlite")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        return null
    }

    private val languages = known.keys.filterNot { it.startsWith("_") }

    @Test
    fun knownWordsAreFound() {
        val failures = mutableListOf<String>()
        var tested = 0
        for (lang in languages) {
            val file = packFile(lang) ?: continue.also { println("RealPacksTest: no $lang pack, skipped") }
            val pack = DictionaryPacks.open(file)
            assertEquals(lang, pack.language)
            assertTrue(pack.meta["attribution"].orEmpty().isNotBlank(), "$lang: attribution in meta")
            assertTrue(pack.meta["license"].orEmpty().isNotBlank(), "$lang: license in meta")
            tested++
            for (w in known.getValue(lang).jsonArray.map { it.jsonObject }) {
                val q = w.getValue("q").jsonPrimitive.content
                val expect = w.getValue("expect").jsonArray.map { it.jsonPrimitive.content }
                val hits = pack.lookup(q, 5)
                if (hits.none { it.headword in expect }) {
                    failures += "$lang lookup($q): want one of $expect, got ${hits.map { "${it.headword}/${it.match}" }}"
                } else if (hits.first { it.headword in expect }.senses.isEmpty()) {
                    failures += "$lang lookup($q): no senses"
                }
                if (w["lemma"]?.jsonPrimitive?.boolean == true) {
                    val lemmas = pack.lemmasOf(q)
                    if (lemmas.none { it in expect }) failures += "$lang lemmasOf($q): want one of $expect, got $lemmas"
                }
            }
        }
        println("RealPacksTest: $tested language packs checked")
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun lookupsStayUnderFiveMilliseconds() {
        val report = mutableListOf<String>()
        for (lang in languages) {
            val file = packFile(lang) ?: continue
            val pack = DictionaryPacks.open(file)
            val queries = known.getValue(lang).jsonArray.map { it.jsonObject.getValue("q").jsonPrimitive.content }
            // Short prefixes are the slowest path (many candidates to rank); include them in the budget.
            val all = queries + queries.map { it.take(1) } + queries.map { it.take(2) } + listOf("zzzzqx")
            repeat(WARMUP) { all.forEach { pack.lookup(it) } }
            val timed = all.map { q -> q to timeMs(pack, q) }.sortedBy { it.second }
            val times = timed.map { it.second }
            val mean = times.average()
            val p95 = times[(times.size * 95) / 100]
            val max = times.last()
            val slowest = timed.takeLast(3).reversed().joinToString { (q, t) -> "%s %.2f".format(q, t) }
            report += "$lang: mean %.2f ms, p95 %.2f ms, max %.2f ms (%d lookups; slowest: %s)"
                .format(mean, p95, max, times.size, slowest)
            println("RealPacksTest timing: ${report.last()}")
            assertTrue(mean < BUDGET_MS, "$lang mean lookup %.2f ms ≥ $BUDGET_MS ms".format(mean))
            assertTrue(p95 < BUDGET_MS, "$lang p95 lookup %.2f ms ≥ $BUDGET_MS ms".format(p95))
        }
    }

    private fun timeMs(pack: DictionaryPack, q: String): Double {
        val rounds = 5
        val start = System.nanoTime()
        repeat(rounds) { pack.lookup(q) }
        return (System.nanoTime() - start) / 1e6 / rounds
    }

    private companion object {
        const val WARMUP = 5
        const val BUDGET_MS = 5.0
    }
}
