package app.tsumugi.kanji

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.db.DictionaryDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Phase 13 against the real content/packs/dictionary.sqlite (BRIEF_V2 §6.15): sound series and roles from
 * tools/packs/build_phonetics.py, the explorer graph, component search and the inflection chip. Skipped when the pack
 * isn't built, or predates the Phase 13 tables.
 */
class RealPhase13PackTest {

    private val pack = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "content/packs/dictionary.sqlite") }
        .firstOrNull { it.exists() }

    private fun open(): Pair<DictionaryDatabase, DictionaryRepository>? = pack?.let {
        val db = DictionaryDatabase(JdbcSqliteDriver("jdbc:sqlite:${it.path}", Properties().apply { put("open_mode", "1") }))
        db to DictionaryRepository(db)
    }

    @Test
    fun soundSeriesRolesAndGraph() = runTest {
        val (db, dictionary) = open() ?: return@runTest println("RealPhase13PackTest skipped: no built pack")
        val explorer = KanjiExplorer(db, dictionary)
        if (explorer.allSeries().isEmpty()) return@runTest println("RealPhase13PackTest skipped: pack predates build_phonetics.py")

        val known = mapOf("青" to "清晴精請情静", "方" to "放訪防房", "反" to "販版坂板飯", "交" to "校効較郊", "包" to "抱砲胞飽泡")
        for ((head, family) in known) {
            val series = assertNotNull(explorer.series(head), head)
            val members = series.members.joinToString("") { it.kanji }
            assertTrue(family.all { it.toString() in members }, "$head: $members")
        }
        val sei = explorer.components("清")
        assertEquals(ComponentRole.PHONETIC, sei.first { it.component == "青" }.role)
        assertEquals(ComponentRole.SEMANTIC, sei.first { it.component == "氵" }.role)
        assertEquals("青", explorer.seriesOf("晴")?.phonetic)

        val graph = assertNotNull(explorer.neighborhood("青", maxNodes = 25))
        assertTrue(graph.nodes.size <= 25)
        assertTrue(graph.nodes.any { it.kind == GraphNodeKind.WORD } && graph.nodes.any { it.kind == GraphNodeKind.SERIES })
        assertEquals(graph.layout(), graph.layout())
        val words = assertNotNull(explorer.wordNeighborhood(dictionary.search("青空").hits.first().entry.id))
        assertTrue(words.nodes.any { it.id == "k:空" })

        assertTrue(explorer.componentSearch("氵 青").kanji.any { it.literal == "清" })
        assertTrue(explorer.componentSearch("言 五 口").kanji.any { it.literal == "語" })

        val elapsed = measureTime { repeat(10) { explorer.neighborhood("語") } }
        println("RealPhase13PackTest: ${elapsed.inWholeMilliseconds / 10} ms per neighborhood")
        assertTrue(elapsed.inWholeMilliseconds / 10 < 100, "neighborhood took ${elapsed / 10}")
    }

    @Test
    fun inflectionChipAndRanksOnRealSearch() = runTest {
        val (_, dictionary) = open() ?: return@runTest println("RealPhase13PackTest skipped: no built pack")
        val hit = dictionary.search("食べさせられなかった").hits.first()
        assertEquals("食べさせられなかった = 食べる + causative + passive + negative + past", hit.inflection?.text)
        val taberu = dictionary.search("食べる").hits.first()
        assertTrue(taberu.chips.common)
        assertTrue((taberu.frequencyRank ?: Int.MAX_VALUE) < 2000, "食べる is a core word: ${taberu.frequencyRank}")
    }
}
