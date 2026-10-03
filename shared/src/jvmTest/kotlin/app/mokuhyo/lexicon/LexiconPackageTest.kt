package app.mokuhyo.lexicon

import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.srs.ReviewService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * BRIEF_PHASE8 C-04 gate: build v1 → import → build v2 with 20 added / 5 changed terms → import shows the correct delta
 * and queues exactly the new terms. The packages are signed by tools/release/lexicon.py (test key; fixtures written by
 * tools/release/test_lexicon.py), so this also proves the Python and Kotlin canonical JSON agree.
 */
class LexiconPackageTest {
    private fun res(name: String) = javaClass.getResource("/lexicon/$name")!!.readText()
    private val keys = parseTrustedKeys(res("test-key.pub.json"))

    @Test
    fun verifiesThePythonSignature() {
        val v1 = LexiconPackage.parse(res("v1.json"))
        assertTrue(v1.termsIntact)
        val p = LexiconVerifier.verify(v1, keys)
        assertIs<Publisher.Verified>(p)
        assertEquals("Test publisher", p.name)
        assertIs<Publisher.Invalid>(LexiconVerifier.verify(v1, emptyList()), "a key the app doesn't ship is not trusted")
    }

    @Test
    fun detectsTampering() {
        val tamperedTerm = LexiconPackage.parse(res("v2.json").replace("用語31", "用語X"))
        assertFalse(tamperedTerm.termsIntact)
        assertIs<Publisher.Invalid>(LexiconVerifier.verify(tamperedTerm, keys))
        val tamperedManifest = LexiconPackage.parse(res("v2.json").replace("\"1.1.0\"", "\"1.2.0\""))
        assertIs<Publisher.Invalid>(LexiconVerifier.verify(tamperedManifest, keys))
        val unsigned = LexiconPackage.parse(res("v1.json").substringBefore(",\n \"signature\"") + "\n}")
        assertIs<Publisher.Unsigned>(LexiconVerifier.verify(unsigned, keys))
    }

    @Test
    fun roundTripDeltaAndReviewQueue() {
        val (_, db) = DatabaseFactory.inMemory()
        val repo = LexiconRepository(db)
        val reviews = ReviewService(db)
        val v1 = LexiconPackage.parse(res("v1.json"))
        val shipped = Track(id = "cuas-base-defense", lang = "ja", title = "t", version = "0.9.0", terms = v1.pkg.terms)
        val r1 = repo.import(v1, LexiconVerifier.verify(v1, keys), "v1.json", shipped, reviews, "me")
        assertTrue(r1.delta.isEmpty, "v1 equals the shipped track")
        assertEquals(0, r1.queued)
        val v2 = LexiconPackage.parse(res("v2.json"))
        val r2 = repo.import(v2, LexiconVerifier.verify(v2, keys), "v2.json", shipped, reviews, "me")
        assertEquals(20, r2.delta.added.size)
        assertEquals(5, r2.delta.changed.size)
        assertEquals((1..5).map { "cuas-%03d".format(it) }, r2.delta.changed.map { it.id })
        assertEquals(0, r2.delta.removed.size)
        assertEquals(20, r2.queued)
        assertEquals(20L, reviews.total("me", "ja"))
        assertEquals((31..50).map { "term:cuas-%03d".format(it) }.toSet(), db.srsQueries.itemsAll().executeAsList().map { it.ref }.toSet())
        // Re-importing is idempotent; the overlay uses v2's terms.
        val again = repo.import(v2, LexiconVerifier.verify(v2, keys), "v2.json", shipped, reviews, "me")
        assertTrue(again.alreadyImported)
        assertEquals(20L, reviews.total("me", "ja"))
        val merged = repo.overlay(shipped)
        assertEquals("1.1.0", merged.version)
        assertEquals(50, merged.terms.size)
        assertTrue(merged.term("cuas-001")!!.term.endsWith("（改）"))
        assertEquals(listOf("ja"), repo.packages("ja").map { it.lang }.distinct())
    }

    @Test
    fun canonicalJsonMatchesPython() {
        val e = LexiconPackage.json.parseToJsonElement("""{"b":1,"a":"é\n\"x\"","c":[true,null,{"z":0,"y":"\u0001"}]}""")
        assertEquals("""{"a":"é\n\"x\"","b":1,"c":[true,null,{"y":"\u0001","z":0}]}""", CanonicalJson.encode(e))
        assertTrue(SemVer.compare("1.10.0", "1.9.2") > 0)
        assertEquals(0, SemVer.compare("1.0", "1.0.0"))
    }
}
