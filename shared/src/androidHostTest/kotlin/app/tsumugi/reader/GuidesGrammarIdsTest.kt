package app.tsumugi.reader

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Every grammar id the guides library links to exists in the grammar pack sources (tools/packs/grammar). */
class GuidesGrammarIdsTest {
    @Test
    fun guideGrammarIdsExistInThePack() {
        val dir = File("../tools/packs/grammar")
        val ids = (1..5).flatMap { n ->
            Regex("\"id\"\\s*:\\s*\"(n$n-[^\"]+)\"").findAll(File(dir, "n$n.json").readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue(ids.size > 300, "read ${ids.size} grammar ids")
        val missing = GuidesLibrary.all.flatMap { g -> g.grammar.filter { it !in ids }.map { "${g.id}: $it" } }
        assertTrue(missing.isEmpty(), "unknown grammar ids: $missing")
    }
}
