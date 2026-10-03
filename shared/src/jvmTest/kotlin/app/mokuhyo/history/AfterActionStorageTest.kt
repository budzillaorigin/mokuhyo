package app.mokuhyo.history

import app.mokuhyo.backup.Backup
import app.mokuhyo.backup.Bundle
import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.opi.AfterActionBrief
import app.mokuhyo.opi.CorrectionsMode
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import app.mokuhyo.opi.TurnFeedbackRecord
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** BRIEF_PHASE8 C-11: the mode, the AAB and the per-turn feedback are stored with the conversation and travel in the bundle. */
class AfterActionStorageTest {
    private fun machine(name: String): Triple<File, ConversationRepository, Backup> {
        val dir = Files.createTempDirectory("mokuhyo-aab-$name").toFile()
        val (_, db) = DatabaseFactory.open(File(dir, "db/mokuhyo.sqlite"))
        db.userQueries.insertLearner(name, name, 0)
        db.settingsQueries.put("learner.corrections.opi", "off", "learner")
        return Triple(dir, ConversationRepository(db), Backup(File(dir, "db/mokuhyo.sqlite"), db, dir, name, "test"))
    }

    @Test
    fun modeAabAndFeedbackRoundTripThroughTheBundle() {
        val (dirA, a, backupA) = machine("A")
        val id = a.newId()
        val rec = TurnFeedbackRecord(1, "Hola", "Hola.", rewrite = "¡Hola!")
        a.addFeedback(id, rec)
        val aab = AfterActionBrief("after_action", "topic", "t", null, 30, 1, listOf("1"), listOf("x"), listOf(rec))
        a.save(id, "A", "es", "TOPIC", "t", 0, StoredConversation(listOf(Turn(Speaker.LEARNER, "Hola"))), null, listOf("1"), null, CorrectionsMode.AFTER_ACTION, aab)
        val bundle = File(dirA, "x.mokuhyo")
        backupA.export(bundle, listOf("es"), Bundle.PacksList(emptyList(), emptyList(), emptyList()))
        val (_, b, backupB) = machine("B")
        backupB.importBundle(bundle)
        val c = assertNotNull(b.get(id))
        assertEquals(CorrectionsMode.AFTER_ACTION, b.mode(c))
        assertEquals(aab, b.aab(c))
        assertEquals(listOf(rec), b.feedback(id))
    }
}
