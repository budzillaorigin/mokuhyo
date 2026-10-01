package app.mokuhyo.lang

import app.mokuhyo.testing.repoFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BRIEF §4: the contract every registered [LanguageModule] must meet, run for all 11 languages. Pack-dependent
 * checks (dictionary hits, Japanese lattice lemmas) run when the pack is built under content/packs/<lang>/ and are
 * reported as skipped otherwise; `tools/gates/gate_lang.sh` builds the packs first so nothing is skipped there.
 */
class LanguageModuleContractTest {
    private val samples = Json.parseToJsonElement(javaClass.getResource("/lang/samples.json")!!.readText()).jsonObject
    private val packs: File? = runCatching { repoFile("content/packs") }.getOrNull()
    private val registry = LanguageRegistry(packs, dictionaryOpener = DictionaryOpeners.default)

    /** gate_lang sets MOKUHYO_REQUIRE_PACKS=1: a missing pack is then a failure, not a skip. */
    private val requirePacks = System.getenv("MOKUHYO_REQUIRE_PACKS") == "1"

    private fun skip(msg: String) {
        if (requirePacks) throw AssertionError("required pack missing: $msg")
        println("SKIP $msg")
    }

    @Test
    fun everyLaunchLanguageIsRegistered() {
        assertEquals(listOf("ja", "es", "fr", "de", "pt-BR", "ru", "zh-Hans", "ko", "ar", "fa", "id"), registry.codes)
        registry.codes.forEach { assertNotNull(samples[it], "no sample for $it") }
    }

    @Test
    fun segmentationCoversTheTextWithCorrectOffsets() = forEach { m, sample ->
        val sentence = sample["sentence"]!!.jsonPrimitive.content
        val tokens = m.segment(sentence)
        tokens.forEach { t -> assertEquals(t.text, sentence.substring(t.start, t.end), "${m.code}: offsets of '${t.text}'") }
        val words = tokens.filter { it.isWord }
        assertTrue(words.size >= sample["minWords"]!!.jsonPrimitive.int, "${m.code}: only ${words.size} words in $words")
        sample["mustContain"]!!.jsonArray.forEach { w ->
            val target = w.jsonPrimitive.content
            assertTrue(words.any { it.text.contains(target) || target.contains(it.text) && it.text.length >= 2 }, "${m.code}: '$target' not among $words")
        }
        // Every non-space character belongs to some token.
        val covered = BooleanArray(sentence.length).also { arr -> tokens.forEach { t -> for (i in t.start until t.end) arr[i] = true } }
        sentence.forEachIndexed { i, c -> if (!c.isWhitespace()) assertTrue(covered[i], "${m.code}: '$c' at $i not covered") }
    }

    @Test
    fun normalizeForCompareFoldsLikeTheFixtures() = forEach { m, sample ->
        sample["compare"]!!.jsonArray.forEach { pair ->
            val (a, b) = pair.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(m.normalizeForCompare(b), m.normalizeForCompare(a), "${m.code}: '$a' vs '$b'")
        }
    }

    @Test
    fun lemmaAlwaysIncludesTheTokenItself() = forEach { m, sample ->
        m.segment(sample["sentence"]!!.jsonPrimitive.content).filter { it.isWord }.forEach { t ->
            assertTrue(t.text in m.lemma(t), "${m.code}: lemma(${t.text}) = ${m.lemma(t)}")
        }
    }

    @Test
    fun japaneseLatticeGivesDictionaryFormsAndFurigana() {
        val ja = registry.module("ja")
        if (registry.packFile("ja", "tokenizer.sqlite") == null) return skip("ja lattice: content/packs/ja/tokenizer.sqlite not built")
        val (surface, base) = samples["ja"]!!.jsonObject["inflected"]!!.jsonArray.map { it.jsonPrimitive.content }
        val tokens = ja.segment(surface)
        assertTrue(tokens.any { base in ja.lemma(it) }, "no $base among ${tokens.map { ja.lemma(it) }}")
        val library = ja.segment("図書館").single()
        assertEquals("としょかん", ja.readingAids.ruby(library))
    }

    @Test
    fun scriptsAndReadingAids() {
        listOf("ar", "fa").forEach { assertEquals(Direction.RTL, registry.module(it).script.direction) }
        assertEquals(Direction.LTR, registry.module("ru").script.direction)
        assertTrue(registry.module("ja").script.needsSegmentation && registry.module("zh-Hans").script.needsSegmentation)
        val zh = registry.module("zh-Hans")
        assertEquals("tú shū guǎn", zh.readingAids.ruby(Token("图书馆", 0, 3, true)))
        assertTrue((zh.readingAids as IcuReadingAids).toTraditional("图书馆") == "圖書館")
        assertEquals("Moskva", registry.module("ru").readingAids.romanize("Москва"))
        assertEquals("مكتبة", registry.module("ar").readingAids.stripVowelMarks("مَكْتَبَةٌ"))
        assertNotNull(registry.module("ko").readingAids.romanize("서울"))
    }

    @Test
    fun dictionaryHitsOnKnownWords() {
        val known = javaClass.getResource("/dictionary/known_words.json")?.readText()
            ?: return skip("dictionary hits: known_words.json not present yet")
        val lists = Json.parseToJsonElement(known).jsonObject
        registry.codes.forEach { code ->
            val m = registry.module(code)
            val dict = m.dictionary ?: return@forEach skip("$code dictionary: content/packs/$code/dictionary.sqlite not built")
            val words = lists[code]?.jsonArray?.map { it.jsonObject["q"]!!.jsonPrimitive.content }.orEmpty()
            val misses = words.filter { dict.lookup(it, 1).isEmpty() }
            assertTrue(misses.isEmpty(), "$code: no dictionary hit for $misses")
            assertTrue(words.size >= 20, "$code: needs 20 known words, has ${words.size}")
        }
    }

    private fun forEach(check: (LanguageModule, kotlinx.serialization.json.JsonObject) -> Unit) {
        registry.codes.forEach { code -> check(registry.module(code), samples[code]!!.jsonObject) }
    }
}
