package app.mokuhyo.feedback

import app.mokuhyo.backup.Backup
import app.mokuhyo.backup.Bundle
import app.mokuhyo.db.DatabaseFactory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** BRIEF_PHASE8 N-10 gate: suggestions round-trip (store → bundle → another computer; standalone export) and the file validates. */
class SuggestionsTest {
    private fun machine(name: String): Triple<File, SuggestionStore, Backup> {
        val dir = Files.createTempDirectory("mokuhyo-sugg-$name").toFile()
        val (_, db) = DatabaseFactory.open(File(dir, "db/mokuhyo.sqlite"))
        db.userQueries.insertLearner(name, name, 0)
        return Triple(dir, SuggestionStore(File(dir, "suggestions.json")), Backup(File(dir, "db/mokuhyo.sqlite"), db, dir, name, "test"))
    }

    @Test
    fun roundTripThroughTheBundleAndStandalone() {
        val (dirA, a, backupA) = machine("A")
        a.add("es", "suggest_term", "term", "", "dron kamikaze", "used on the news for loitering munitions")
        a.add("es", "flag", "passage", "es-dr-2-news-001", "The answer key looks wrong for question 2.")
        val bundle = File(dirA, "a.mokuhyo")
        backupA.export(bundle, listOf("es"), Bundle.PacksList(emptyList(), emptyList(), emptyList()))
        val (dirB, b, backupB) = machine("B")
        b.add("es", "flag", "turn", "conv-1:3", "The correction changed my meaning.")
        backupB.importBundle(bundle)
        assertEquals(3, b.all().size)
        backupB.importBundle(bundle)
        assertEquals(3, b.all().size, "merging twice adds nothing")
        val standalone = File(dirB, "export.json")
        b.exportTo(standalone)
        assertEquals(3, b.decode(standalone.readText()).suggestions.size)
        assertEquals(SuggestionFile.FORMAT, b.decode(standalone.readText()).format)
    }

    @Test
    fun invalidInputIsRefused() {
        val (_, s, _) = machine("C")
        assertFailsWith<IllegalArgumentException> { s.add("es", "complaint", "term", "", "x") }
        assertFailsWith<IllegalArgumentException> { s.add("es", "flag", "term", "", "  ") }
        assertFailsWith<IllegalArgumentException> { s.decode("""{"format":"other","suggestions":[]}""") }
    }
}
