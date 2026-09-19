package app.tsumugi.kanji

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.DictionaryDatabase
import app.tsumugi.dictionary.db.Kanji
import app.tsumugi.dictionary.db.Kanji_component_role
import app.tsumugi.dictionary.db.Kanji_element
import app.tsumugi.dictionary.db.Phonetic_series
import app.tsumugi.domain.ItemKind
import app.tsumugi.srs.CardState
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KanjiExplorerTest {

    private val pack: DictionaryDatabase = DictionaryFixture.create().also(::addParts)
    private val dictionary = DictionaryRepository(pack)
    private val explorer = KanjiExplorer(pack, dictionary)

    /** 青 and its sound series (清 晴 静), shaped like tools/packs/build_phonetics.py output. */
    private fun addParts(db: DictionaryDatabase) {
        val q = db.dictionaryQueries
        fun kanji(lit: String, freq: Long?, jlpt: Long?, on: String, meaning: String) =
            q.insertKanji(Kanji(lit, 1, 8, freq, null, jlpt, null, null, "[\"$meaning\"]", on, "", ""))
        kanji("青", 589, 4, "[\"セイ\",\"ショウ\"]", "blue")
        kanji("清", 705, 2, "[\"セイ\",\"ショウ\"]", "pure")
        kanji("晴", 1004, 3, "[\"セイ\"]", "clear up")
        kanji("静", 764, 3, "[\"セイ\",\"ジョウ\"]", "quiet")
        kanji("日", 1, 5, "[\"ニチ\"]", "day")
        val p = db.kanjiPartsQueries
        listOf(
            Kanji_element("清", "氵", 1, "left"), Kanji_element("清", "青", 1, "right"), Kanji_element("清", "月", 2, "right"),
            Kanji_element("晴", "日", 1, "left"), Kanji_element("晴", "青", 1, "right"),
            Kanji_element("静", "青", 1, "left"), Kanji_element("静", "争", 1, "right"),
            Kanji_element("青", "月", 1, "bottom"),
        ).forEach(p::insertElement)
        val phon = """{"match":"same","reading":"セイ","kvgPhon":true,"kvgRadical":false}"""
        p.insertRole(Kanji_component_role("清", "氵", 0, "SEMANTIC", """{"kvgPhon":false,"kvgRadical":true}""", "", "derived"))
        p.insertRole(Kanji_component_role("清", "青", 1, "PHONETIC", phon, "青", "derived"))
        p.insertRole(Kanji_component_role("晴", "日", 0, "SEMANTIC", "{}", "", "verified"))
        p.insertRole(Kanji_component_role("晴", "青", 1, "PHONETIC", phon, "青", "verified"))
        p.insertSeries(
            Phonetic_series(
                "青", "[\"セイ\",\"ショウ\"]",
                """[{"k":"青","on":["セイ","ショウ"],"match":"self"},{"k":"清","on":["セイ"],"match":"same"},{"k":"晴","on":["セイ"],"match":"same"},{"k":"静","on":["セイ","ジョウ"],"match":"same"}]""",
                4, "derived",
            ),
        )
    }

    @Test
    fun componentsCarryTheirRolesAndFallBackToKradfile() = runTest {
        val parts = explorer.components("清")
        assertEquals(listOf("氵" to ComponentRole.SEMANTIC, "青" to ComponentRole.PHONETIC), parts.map { it.component to it.role })
        val phonetic = parts[1]
        assertEquals("セイ", phonetic.reading)
        assertEquals("same", phonetic.match)
        assertEquals("青", phonetic.seriesId)
        assertEquals("right", phonetic.position)
        assertTrue(phonetic.derived && phonetic.kanjiVgPhonetic)
        assertFalse(explorer.components("晴")[1].derived, "a reviewed series flips its roles to verified")
        // No roles and no KanjiVG tree in the pack for 食: KRADFILE parts, no roles.
        assertEquals(listOf("人", "良"), explorer.components("食").map { it.component })
        assertTrue(explorer.components("食").all { it.role == null })
        // KanjiVG tree without roles: parts with positions.
        assertEquals(listOf("青" to "left", "争" to "right"), explorer.components("静").map { it.component to it.position }.sortedBy { it.second })
    }

    @Test
    fun soundSeries() = runTest {
        val series = assertNotNull(explorer.seriesOf("清"))
        assertEquals("青", series.phonetic)
        assertEquals(listOf("セイ", "ショウ"), series.readings)
        assertEquals("清晴静", series.family.joinToString("") { it.kanji })
        assertTrue(series.derived)
        assertEquals(series, explorer.seriesOf("青"), "the phonetic itself heads its series")
        assertNull(explorer.seriesOf("食"))
        assertEquals(listOf("青"), explorer.allSeries().map { it.phonetic })
    }

    @Test
    fun neighborhoodIsCappedAndOneHop() = runTest {
        val graph = assertNotNull(explorer.neighborhood("青", maxNodes = 5))
        assertTrue(graph.nodes.size <= 5, "cap: ${graph.nodes.size}")
        assertEquals("k:青", graph.focusId)
        assertTrue(graph.nodes.first().isFocus && graph.nodes.count { it.isFocus } == 1)
        assertTrue(graph.edges.all { it.from == graph.focusId || it.to == graph.focusId }, "focus mode shows one hop")
        assertTrue(graph.nodes.any { it.label == "月" && it.kind == GraphNodeKind.COMPONENT })
        assertTrue(graph.nodes.any { it.kind == GraphNodeKind.SERIES }, "sound-series siblings are neighbors")
        assertTrue(graph.hidden > 0, "3 siblings don't fit next to the part in 5 nodes")

        val big = assertNotNull(explorer.neighborhood("青", maxNodes = 30))
        assertEquals(setOf("k:青", "k:月", "k:清", "k:晴", "k:静"), big.nodes.map { it.id }.toSet())
        assertEquals(0, big.hidden)
        // JLPT coloring: N4 → bucket 1, N2 → bucket 3; frequency coloring: 589 → bucket 1 (≤ 1000), 705 → 1.
        assertEquals(1, big.nodes.first { it.id == "k:青" }.colorBucket)
        assertEquals(3, big.nodes.first { it.id == "k:清" }.colorBucket)
        val byFreq = assertNotNull(explorer.neighborhood("青", coloring = GraphColoring.FREQUENCY))
        assertEquals(1, byFreq.nodes.first { it.id == "k:清" }.colorBucket)
        assertEquals(KanjiExplorer.UNKNOWN_BUCKET, byFreq.nodes.first { it.id == "k:月" }.colorBucket, "月 isn't in this pack")

        val food = assertNotNull(explorer.neighborhood("食"))
        val word = food.nodes.first { it.kind == GraphNodeKind.WORD }
        assertEquals("w:${DictionaryFixture.TABERU}", word.id)
        assertEquals(DictionaryFixture.TABERU, word.entryId)
        assertEquals("たべる", word.reading)
        assertTrue(food.nodes.any { it.label == "人" && it.kind == GraphNodeKind.COMPONENT })
        assertNull(explorer.neighborhood("鬱"))
    }

    @Test
    fun wordNeighborhoodReCentersOnAWord() = runTest {
        val graph = assertNotNull(explorer.wordNeighborhood(DictionaryFixture.TABERU))
        assertEquals("w:${DictionaryFixture.TABERU}", graph.focusId)
        assertTrue(graph.nodes.any { it.id == "k:食" && it.kind == GraphNodeKind.KANJI })
        assertTrue(graph.edges.any { it.from == graph.focusId && it.to == "k:食" })
    }

    @Test
    fun componentCombinationSearch() = runTest {
        assertEquals(listOf("清"), explorer.componentSearch("氵 青").kanji.map { it.literal })
        assertEquals(listOf("清"), explorer.componentSearch("氵+青").kanji.map { it.literal })
        // KRADFILE radicals count too (田 in 猫 and 畑), and the typed part itself isn't a result.
        assertEquals(setOf("猫", "畑"), explorer.componentSearch("田").kanji.map { it.literal }.toSet())
        val none = explorer.componentSearch("青 ☃")
        assertTrue(none.kanji.isEmpty())
        assertEquals(listOf("☃"), none.unknown)
        assertEquals("氵 青", explorer.componentQuery("清"))
        assertTrue(explorer.componentSearch("  ").components.isEmpty())
    }

    @Test
    fun layoutIsDeterministic() = runTest {
        val graph = assertNotNull(explorer.neighborhood("青"))
        val a = graph.layout()
        val b = graph.layout()
        assertEquals(a, b, "same graph, same picture")
        assertEquals(graph.nodes.size, a.size)
        val focus = a.first { it.id == graph.focusId }
        assertEquals(0.5, focus.x)
        assertEquals(0.5, focus.y)
        assertTrue(a.all { it.x in 0.0..1.0 && it.y in 0.0..1.0 && !it.x.isNaN() })
        assertEquals(a.size, a.map { it.x to it.y }.distinct().size, "no two nodes on top of each other")
        // Linked nodes sit closer to the focus than the unit square's far corners.
        assertTrue(a.filter { it.id != graph.focusId }.all { (it.x - 0.5) * (it.x - 0.5) + (it.y - 0.5) * (it.y - 0.5) < 0.5 })
        assertEquals(ForceLayout.layout(listOf("a", "b", "c"), listOf("a" to "b")), ForceLayout.layout(listOf("a", "b", "c"), listOf("a" to "b")))
        assertEquals(listOf(NodePosition("x", 0.5, 0.5)), ForceLayout.layout(listOf("x"), emptyList()))
        assertTrue(ForceLayout.layout(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun bookmarkToSrs() = runTest {
        val clock = TestClock()
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val srs = SrsRepository(db, "device", clock)
        val bookmarks = KanjiBookmarks(srs) { null }
        val info = assertNotNull(dictionary.kanji("食")).info
        assertFalse(bookmarks.isBookmarked("食"))
        assertEquals("k:食", bookmarks.bookmark(info))
        assertTrue(bookmarks.isBookmarked("食"))
        val item = assertNotNull(srs.item("k:食"))
        assertEquals(ItemKind.KANJI, item.kind)
        assertEquals(listOf("しょく", "じき", "くう", "たべる"), item.acceptedReadings)
        assertEquals(listOf("eat", "food"), item.meanings)
        assertTrue(srs.cardsForItems(listOf("k:食")).all { it.fsrs.state == CardState.LEARNING })
        assertEquals("k:食", bookmarks.bookmark(info), "twice is harmless")
        assertEquals("きよい", KanjiBookmarks.kunStem("きよ.い"))
    }
}
