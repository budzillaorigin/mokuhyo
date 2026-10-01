package app.mokuhyo.dictionary

import app.mokuhyo.dictionary.db.DictionaryDatabase
import app.mokuhyo.dictionary.db.Entry
import app.mokuhyo.dictionary.db.Fold
import app.mokuhyo.dictionary.db.Form
import app.mokuhyo.dictionary.db.Meta
import app.mokuhyo.lang.DictEntry.Match
import app.mokuhyo.testing.inMemoryDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lookup paths and ranking of [SqlDictionaryPack] on tiny packs shaped like build_dictionary.py output. */
class SqlDictionaryPackTest {

    private class Fixture(val language: String) {
        val db = DictionaryDatabase(inMemoryDriver(DictionaryDatabase.Schema))
        private val q = db.dictionaryQueries
        private var nextId = 1L

        fun entry(
            headword: String,
            rank: Long?,
            vararg glosses: String,
            reading: String? = null,
            pos: String = "noun",
            forms: List<String> = emptyList(),
        ): Long {
            val id = nextId++
            q.insertEntry(Entry(id, language, headword, reading, pos, rank))
            glosses.forEachIndexed { i, g -> q.insertSense(app.mokuhyo.dictionary.db.Sense(id, i.toLong(), g, null, null)) }
            // Like build_dictionary.py: only keys that differ from their text get a fold row.
            for (text in listOfNotNull(headword, reading)) fold(text, id, 0, rank)
            for (f in forms) {
                q.insertForm(Form(f, id, "test"))
                fold(f, id, SqlDictionaryPack.FOLD_KIND_FORM, rank)
            }
            return id
        }

        private fun fold(text: String, id: Long, kind: Long, rank: Long?) {
            val key = DictionaryFold.fold(text, language)
            if (key != text) q.insertFold(Fold(key, id, kind, rank))
        }

        fun pack() = SqlDictionaryPack(db, language)
    }

    private val es = Fixture("es").apply {
        val hablar = entry("hablar", 50, "to speak", "to talk", pos = "verb", forms = listOf("hablamos", "habló", "hablo"))
        db.dictionaryQueries.insertExample(
            app.mokuhyo.dictionary.db.Example(hablar, 0, "Hablo español.", "I speak Spanish.", "wiktionary"),
        )
        entry("habla", 900, "speech")
        entry("hablador", 5000, "talkative", pos = "adj")
        entry("hablantín", null, "chatterbox")
        entry("niño", 300, "child, boy")
        entry("árbol", 800, "tree")
        entry("comer", 100, "to eat", pos = "verb", forms = listOf("como", "comimos"))
        entry("como", 10, "as, like", pos = "conj")
        db.dictionaryQueries.insertMeta(Meta("language", "es"))
        db.dictionaryQueries.insertMeta(Meta("license", "CC BY-SA 3.0"))
    }.pack()

    private val ja = Fixture("ja").apply {
        entry("食べる", 400, "to eat", reading = "たべる", pos = "v1,vt", forms = listOf("喰べる"))
        entry("テレビ", 700, "television; TV")
        entry("食べ物", 900, "food", reading = "たべもの")
        entry("たべっこ", null, "(not a real word; prefix ranking)")
    }.pack()

    @Test
    fun exactHeadwordComesFirst() {
        val hits = es.lookup("hablar")
        assertEquals("hablar", hits.first().headword)
        assertEquals(Match.EXACT, hits.first().match)
        assertEquals(listOf("to speak", "to talk"), hits.first().senses.map { it.glossEn })
        assertEquals("to speak; to talk", hits.first().shortGloss)
        assertEquals("I speak Spanish.", hits.first().examples.single().translation)
    }

    @Test
    fun exactReadingMatches() {
        val hit = ja.lookup("たべる").first()
        assertEquals("食べる", hit.headword)
        assertEquals("たべる", hit.reading)
        assertEquals(Match.EXACT, hit.match)
    }

    @Test
    fun inflectedFormResolvesToLemma() {
        val hit = es.lookup("hablamos").first()
        assertEquals("hablar", hit.headword)
        assertEquals(Match.LEMMA, hit.match)
        assertEquals("食べる", ja.lookup("喰べる").first().headword)
    }

    @Test
    fun exactBeatsLemmaForAmbiguousSurface() {
        val hits = es.lookup("como")
        assertEquals(listOf("como" to Match.EXACT, "comer" to Match.LEMMA), hits.take(2).map { it.headword to it.match })
    }

    @Test
    fun fuzzyIgnoresCaseAccentsAndWidth() {
        assertEquals("árbol" to Match.FUZZY, es.lookup("Arbol").first().let { it.headword to it.match })
        assertEquals("niño" to Match.FUZZY, es.lookup("NINO").first().let { it.headword to it.match })
        assertEquals("niño", es.lookup("ｎｉｎｏ").first().headword)
        // Sentence-initial capital on an inflected form: the folded form index.
        assertEquals("hablar" to Match.FUZZY, es.lookup("Hablamos").first().let { it.headword to it.match })
        assertEquals("hablar", es.lookup("hablo").first().headword)
    }

    @Test
    fun fuzzyFoldsKatakanaToHiragana() {
        assertEquals("テレビ" to Match.EXACT, ja.lookup("テレビ").first().let { it.headword to it.match })
        assertEquals("テレビ" to Match.FUZZY, ja.lookup("てれび").first().let { it.headword to it.match })
        assertEquals("食べる" to Match.FUZZY, ja.lookup("タベル").first().let { it.headword to it.match })
    }

    @Test
    fun prefixMatchesRankByFrequencyWithUnrankedLast() {
        val hits = es.lookup("habl")
        assertEquals(listOf("hablar", "habla", "hablador", "hablantín"), hits.map { it.headword })
        assertTrue(hits.all { it.match == Match.PREFIX })
        assertEquals(listOf(50, 900, 5000, null), hits.map { it.frequencyRank })
        // Through the fold table: the headword itself starts with "á".
        assertEquals("árbol" to Match.PREFIX, es.lookup("Arb").single().let { it.headword to it.match })
    }

    @Test
    fun stagesAreDeduplicatedAndOrdered() {
        val hits = es.lookup("habla")
        assertEquals(listOf("habla", "hablar", "hablador", "hablantín"), hits.map { it.headword })
        assertEquals(listOf(Match.EXACT, Match.PREFIX, Match.PREFIX, Match.PREFIX), hits.map { it.match })
        val jaHits = ja.lookup("たべ")
        assertEquals(listOf("食べる", "食べ物", "たべっこ"), jaHits.map { it.headword })
    }

    @Test
    fun limitAndEmptyQueries() {
        assertEquals(2, es.lookup("habl", limit = 2).size)
        assertEquals(emptyList(), es.lookup("   "))
        assertEquals(emptyList(), es.lookup("hablar", limit = 0))
        assertEquals(emptyList(), es.lookup("zzz"))
    }

    @Test
    fun lemmasOfUsesTheFormIndexOnly() {
        assertEquals(listOf("hablar"), es.lemmasOf("hablamos"))
        assertEquals(listOf("hablar"), es.lemmasOf("Hablamos"))
        assertEquals(listOf("comer"), es.lemmasOf("como"))
        assertEquals(emptyList(), es.lemmasOf("hablar"))
        assertEquals(emptyList(), es.lemmasOf("xyz"))
    }

    @Test
    fun entryByIdAndMeta() {
        val id = es.lookup("árbol").first().id
        val e = assertNotNull(es.entry(id))
        assertEquals("árbol", e.headword)
        assertEquals(800, e.frequencyRank)
        assertNull(es.entry(9999))
        assertEquals("CC BY-SA 3.0", es.meta["license"])
        assertEquals("es", es.language)
    }

    @Test
    fun queryIsNfcNormalized() {
        // "árbol" typed with a combining acute (NFD) still matches exactly.
        assertEquals(Match.EXACT, es.lookup("árbol").first().match)
    }
}
