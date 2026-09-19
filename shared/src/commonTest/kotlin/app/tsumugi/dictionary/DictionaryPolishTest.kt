package app.tsumugi.dictionary

import app.tsumugi.dictionary.db.Freq_word
import app.tsumugi.jp.Deinflector
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** BRIEF_V2 §6.15 dictionary polish: inflection chip, frequency/common chips, instant search. */
class DictionaryPolishTest {

    private val pack = DictionaryFixture.create().also {
        it.dictionaryQueries.insertFreqWord(Freq_word(1, DictionaryFixture.WATASHI, 900))
        it.dictionaryQueries.insertFreqWord(Freq_word(12, DictionaryFixture.TABERU, 300))
    }
    private val repo = DictionaryRepository(pack)

    @Test
    fun inflectionBreakdownChip() = runTest {
        val hit = repo.search("食べさせられなかった").hits.first()
        assertEquals(MatchKind.DEINFLECTED, hit.match)
        val chip = assertNotNull(hit.inflection)
        assertEquals("食べさせられなかった = 食べる + causative + passive + negative + past", chip.text)
        assertEquals("causative + passive + negative + past", chip.chain)
        assertEquals(listOf("causative", "passive", "negative", "past"), chip.steps.map { it.reason })

        assertEquals("書きました = 書く + polite past", repo.search("書きました").hits.first().inflection?.text)
        assertNull(repo.search("食べる").hits.first().inflection, "an exact hit gets no chip")
        assertNull(repo.search("cat").hits.first().inflection)
    }

    @Test
    fun everyDeinflectorReasonHasALabel() {
        val reasons = app.tsumugi.jp.DeinflectRules.all.map { it.reason }.toSet()
        val plain = setOf(
            "causative", "passive", "negative", "past", "polite", "polite negative", "polite past", "polite past negative",
            "potential", "volitional", "polite volitional", "imperative", "prohibitive", "conditional", "adverbial",
            "attributive", "copula", "progressive",
        )
        val unlabeled = reasons.filter { it !in plain && InflectionBreakdown.label(it) == it }
        assertTrue(unlabeled.isEmpty(), "reasons without a learner-facing label: $unlabeled")
        assertEquals("〜たい (want to)", InflectionBreakdown.label("-tai"))
        // The chain comes straight from the Deinflector's rule chain.
        val d = Deinflector.deinflect("食べたくない").first { it.term == "食べる" }
        assertEquals("食べたくない = 食べる + 〜たい (want to) + negative", InflectionBreakdown.of("食べたくない", d.term, d.reasons)?.text)
    }

    @Test
    fun frequencyRankAndCommonChips() = runTest {
        val taberu = repo.search("食べる").hits.first()
        assertEquals(12, taberu.frequencyRank)
        assertEquals(listOf("common", "N5", "#12"), taberu.chips.labels)
        val sushi = repo.search("寿司").hits.first()
        assertNull(sushi.frequencyRank, "not on the frequency list")
        assertEquals(listOf("common"), sushi.chips.labels)
        assertEquals(1, repo.search("I").hits.first { it.entry.id == DictionaryFixture.WATASHI }.frequencyRank)
    }

    @Test
    fun instantSearchDebouncesAndCancels() = runTest {
        // Lookups are precomputed so the fake stays on the test dispatcher (the real one hops to Dispatchers.IO).
        val results = listOf("食べる", "猫", "寿司").associateWith { repo.search(it) }
        val asked = mutableListOf<String>()
        val search = InstantSearch({ q -> asked += q; results.getValue(q) }, debounce = 100.milliseconds, scope = this)
        search.update("た")
        advanceTimeBy(50)
        search.update("たべ")
        advanceTimeBy(50)
        search.update("食べる")
        assertTrue(search.state.value.searching)
        assertEquals("食べる", search.state.value.query)
        advanceUntilIdle()
        assertEquals(listOf("食べる"), asked, "only the last query of a burst is looked up")
        val state = search.state.value
        assertFalse(state.searching)
        assertEquals("食べる", state.resultsFor)
        assertEquals("食べる", state.results.hits.first().entry.headword)

        // A new keystroke keeps the old results on screen until the new ones land.
        search.update("猫")
        runCurrent()
        assertEquals("食べる", search.state.value.resultsFor)
        advanceUntilIdle()
        assertEquals("猫", search.state.value.results.hits.first().entry.headword)

        // Blank clears at once; submit skips the debounce.
        search.update("")
        assertTrue(search.state.value.results.hits.isEmpty())
        search.submit("寿司")
        runCurrent()
        assertEquals("寿司", search.state.value.results.hits.first().entry.headword)
        assertEquals(listOf("食べる", "猫", "寿司"), asked)
    }

    @Test
    fun instantSearchDropsAStaleSlowLookup() = runTest {
        val seen = mutableListOf<String>()
        val results = listOf("slow", "cat").associateWith { repo.search(it) }
        val search = InstantSearch(
            { q -> if (q == "slow") kotlinx.coroutines.delay(500); results.getValue(q) },
            debounce = 10.milliseconds, scope = this, onChange = { if (!it.searching) seen += it.resultsFor },
        )
        search.update("slow")
        advanceTimeBy(200) // the slow lookup is running
        search.update("cat")
        advanceUntilIdle()
        assertEquals(listOf("cat"), seen, "the cancelled lookup never publishes")
        assertEquals("猫", search.state.value.results.hits.first().entry.headword)
    }
}
