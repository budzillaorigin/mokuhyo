package app.mokuhyo.backup

import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.history.ConversationRepository
import app.mokuhyo.history.HistoryRepository
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import app.mokuhyo.srs.Rating
import app.mokuhyo.srs.ReviewService
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BRIEF §11.1 gate_data, cross-OS: CI writes fixture bundles on every OS (MOKUHYO_MAKE_FIXTURE_BUNDLES=dir), then each
 * OS imports every OS's bundles (MOKUHYO_FIXTURE_BUNDLES=dir). Locally both run against the same machine.
 */
class CrossOsBundleTest {
    private val passphrase = "fixture passphrase ü 日本"

    private fun machine(): Triple<File, Backup, app.mokuhyo.db.MokuhyoDatabase> {
        val dir = Files.createTempDirectory("mokuhyo-xos").toFile()
        val (_, db) = DatabaseFactory.open(File(dir, "db/mokuhyo.sqlite"))
        db.userQueries.insertLearner("L-${System.getProperty("os.name")}", "Fixture", 0)
        return Triple(dir, Backup(File(dir, "db/mokuhyo.sqlite"), db, dir, "L-${System.getProperty("os.name")}", "fixture"), db)
    }

    @Test
    fun makeAndImportFixtureBundles() {
        val makeDir = System.getenv("MOKUHYO_MAKE_FIXTURE_BUNDLES")?.let(::File)
        val importDir = System.getenv("MOKUHYO_FIXTURE_BUNDLES")?.let(::File)
        val local = Files.createTempDirectory("bundles").toFile()
        val out = makeDir ?: local
        out.mkdirs()
        val os = System.getProperty("os.name").lowercase().replace(Regex("[^a-z]"), "")
        // Make: one plain and one encrypted bundle with non-ASCII content and a recording.
        val (dir, backup, db) = machine()
        val reviews = ReviewService(db)
        val item = reviews.add("L-${System.getProperty("os.name")}", "ar", ReviewService.Kind.WORD, "dict:مكتبة", "مكتبة", "library")
        reviews.grade(item, Rating.GOOD)
        val conv = ConversationRepository(db)
        val id = "fixture-$os"
        File(dir, "recordings/$id").mkdirs()
        val wav = ByteArray(2048) { it.toByte() }
        File(dir, "recordings/$id/turn-01.wav").writeBytes(wav)
        conv.addRecording(id, 1, "recordings/$id/turn-01.wav", 64, java.security.MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) })
        conv.save(id, "L-${System.getProperty("os.name")}", "ja", "OPI", null, 0, StoredConversation(listOf(Turn(Speaker.PARTNER, "お名前は？"), Turn(Speaker.LEARNER, "ブディです。"))), null, listOf("1"), "recordings/$id")
        HistoryRepository(db)
        backup.export(File(out, "$os-plain.mokuhyo"), listOf("ja", "ar"), Bundle.PacksList(emptyList(), emptyList(), emptyList()))
        backup.export(File(out, "$os-encrypted.mokuhyo"), listOf("ja", "ar"), Bundle.PacksList(emptyList(), emptyList(), emptyList()), passphrase)

        // Import: every bundle found (other OSes' in CI, this machine's locally).
        val source = importDir ?: out
        val bundles = source.walkTopDown().filter { it.isFile && it.name.endsWith(".mokuhyo") }.toList()
        assertTrue(bundles.isNotEmpty(), "no bundles in $source")
        // Each bundle into a fresh computer, then all into one (merges across OSes stay distinct and idempotent).
        bundles.forEach { b ->
            val (_, fresh, _) = machine()
            val r = fresh.importBundle(b, if (b.name.contains("encrypted")) passphrase else null)
            assertEquals(1L, r.added["conversation"], "${b.name}: conversation")
            assertEquals(1L, r.added["review"], "${b.name}: review")
            assertEquals(1, r.recordingsCopied, "${b.name}: recording")
        }
        val (_, target, tdb) = machine()
        bundles.forEach { b -> target.importBundle(b, if (b.name.contains("encrypted")) passphrase else null) }
        val origins = bundles.map { it.name.substringBefore('-') }.distinct().size
        assertEquals(origins.toLong(), tdb.historyQueries.conversationsAll().executeAsList().size.toLong())
        println("cross-OS: imported ${bundles.size} bundles from $origins OS(es): ${bundles.joinToString { it.name }}")
    }
}
