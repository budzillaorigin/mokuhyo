package app.mokuhyo.backup

import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.history.ConversationRepository
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.opi.OpiRating
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import app.mokuhyo.testing.TestClock
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * QC Q2: a no-model self-rating saved after the interview is a new fact. It must not replace the completed conversation,
 * and it must reach a computer that imported the unrated version earlier, in both directions and idempotently.
 */
class SelfRatingMergeTest {
    private class Learner(val id: String) {
        val dir: File = Files.createTempDirectory("mokuhyo-rate-$id").toFile()
        val opened = DatabaseFactory.open(File(dir, "db/mokuhyo.sqlite"))
        val db get() = opened.second
        val clock = TestClock()
        val conversations = ConversationRepository(db, clock)
        val backup = Backup(File(dir, "db/mokuhyo.sqlite"), db, dir, id, "test")
        init { db.userQueries.insertLearner(id, "Learner $id", 0) }
        fun bundle(name: String) = File(dir, name).also { backup.export(it, listOf("es"), Bundle.PacksList(listOf("es"), emptyList(), emptyList())) }
        fun visible() = conversations.list(id, "es")
        fun close() { opened.first.close(); dir.deleteRecursively() }
    }

    @Test fun selfRatingIsAppendedAndMergesIntoAnEarlierImportBothWays() {
        val a = Learner("A")
        val b = Learner("B")
        try {
            val id = a.conversations.newId()
            val stored = StoredConversation(listOf(Turn(Speaker.PARTNER, "¿Qué hiciste ayer?"), Turn(Speaker.LEARNER, "Fui al mercado.")))
            fun save(r: OpiRating?) = a.conversations.save(id, "A", "es", "OPI", null, 0, stored, r, emptyList(), "recordings/$id")
            save(OpiRating(null, needsSelfRating = true))
            val original = a.db.historyQueries.conversation(id).executeAsOne()
            b.backup.importBundle(a.bundle("before.mokuhyo"))
            assertNull(b.conversations.rating(b.visible().single())!!.estimate)

            a.clock.advance(5.minutes)
            save(OpiRating("2", selfRated = true))
            assertEquals(original, a.db.historyQueries.conversation(id).executeAsOne(), "the completed row is never rewritten")
            val after = a.bundle("after.mokuhyo")
            repeat(2) { b.backup.importBundle(after) }
            a.backup.importBundle(b.bundle("back.mokuhyo"))
            a.backup.importBundle(a.bundle("self.mokuhyo"))

            for (l in listOf(a, b)) {
                val shown = l.visible()
                assertEquals(listOf(id), shown.map { it.id }, "one visible conversation on ${l.id}")
                val rating = l.conversations.rating(shown.single())!!
                assertEquals("2", rating.estimate, "the self-rating reached ${l.id}")
                assertTrue(rating.selfRated)
                assertEquals("2", l.conversations.rating(l.conversations.get(id)!!)!!.estimate)
                assertEquals(stored.transcript, l.conversations.stored(shown.single()).transcript)
                assertEquals(original.turnsJson, l.db.historyQueries.conversation(id).executeAsOne().turnsJson, "original bytes on ${l.id}")
                assertEquals(original.ratingJson, l.db.historyQueries.conversation(id).executeAsOne().ratingJson)
                assertEquals(2, l.db.historyQueries.conversationsAll().executeAsList().size, "original + one revision on ${l.id}")
            }
            // Saving the same rating again adds nothing.
            save(OpiRating("2", selfRated = true))
            assertEquals(2, a.db.historyQueries.conversationsAll().executeAsList().size)

            // A deletion on B stays a deletion: re-importing A's rated bundle does not bring the conversation back.
            b.conversations.tombstone(id)
            b.backup.importBundle(after)
            assertTrue(b.visible().isEmpty())
            a.backup.importBundle(b.bundle("deleted.mokuhyo"))
            assertTrue(a.visible().isEmpty(), "the tombstone propagates to A")
            a.backup.importBundle(after)
            assertTrue(a.visible().isEmpty())
        } finally {
            a.close(); b.close()
        }
    }
}
