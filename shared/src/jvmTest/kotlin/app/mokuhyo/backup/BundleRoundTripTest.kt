package app.mokuhyo.backup

import app.cash.sqldelight.db.SqlDriver
import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.exam.ExamAssembler
import app.mokuhyo.exam.ExamBlueprint
import app.mokuhyo.exam.ExamItem
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.ExamSession
import app.mokuhyo.exam.FormLength
import app.mokuhyo.exam.SectionBlueprint
import app.mokuhyo.exam.Skill
import app.mokuhyo.history.ConversationRepository
import app.mokuhyo.history.HistoryRepository
import app.mokuhyo.history.StoredConversation
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import app.mokuhyo.srs.Rating
import app.mokuhyo.srs.ReviewService
import app.mokuhyo.testing.TestClock
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** BRIEF §8.2 / §11.1 gate_data: export → import is lossless and merge-idempotent, plain and encrypted. */
class BundleRoundTripTest {
    private class Machine(val learner: String) {
        val dir: File = Files.createTempDirectory("mokuhyo-$learner").toFile()
        val opened: Pair<SqlDriver, MokuhyoDatabase> = DatabaseFactory.open(File(dir, "db/mokuhyo.sqlite"))
        val driver get() = opened.first
        val db get() = opened.second
        val clock = TestClock()
        val history = HistoryRepository(db, clock)
        val reviews = ReviewService(db, clock = clock)
        val conversations = ConversationRepository(db, clock)
        val backup = Backup(File(dir, "db/mokuhyo.sqlite"), db, dir, learner, "test")

        init {
            db.userQueries.insertLearner(learner, "Learner $learner", 0)
            db.settingsQueries.put("learner.language", "es", "learner")
        }

        fun count(table: String) = driver.executeQuery(null, "SELECT count(*) FROM $table", { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0)!! else 0L) }, 0).value
    }

    private val levels = listOf("0+", "1", "1+", "2", "2+", "3")
    private val bp = SectionBlueprint(levels, levels.associateWith { 3 }).let { ExamBlueprint("es", reading = it, listening = it) }
    private val passages = levels.flatMap { l -> (1..3).map { ExamPassage("$l-$it", Skill.READING.exam, "es", l, "news", "t", "b", emptyList(), "llm", false) } }.associateBy { it.id }
    private val items = passages.values.flatMap { p -> (1..3).map { ExamItem("${p.id}-$it", "b", p.exam, p.level, "detail", p.id, "s", listOf("a", "b", "c", "d"), 0, "", emptyList(), emptyList(), "llm", false) } }

    private fun populate(m: Machine, seed: Int) {
        val s = ExamSession(ExamAssembler.test(bp, Skill.READING, FormLength.FULL, items, passages, emptySet(), Random(seed)), clock = m.clock)
        s.form.items.forEachIndexed { i, f -> s.goTo(i); s.choose(f.item.answer) }
        m.history.saveAttempt(m.learner, Skill.READING, s.submit())
        val item = m.reviews.add(m.learner, "es", ReviewService.Kind.WORD, "dict:42", "biblioteca", "library")
        m.reviews.grade(item, Rating.GOOD)
        val conv = m.conversations.newId()
        File(m.dir, "recordings/$conv").mkdirs()
        val wav = ByteArray(1000) { (it * seed).toByte() }
        File(m.dir, "recordings/$conv/turn-01.wav").writeBytes(wav)
        m.conversations.addRecording(conv, 1, "recordings/$conv/turn-01.wav", 31, java.security.MessageDigest.getInstance("SHA-256").digest(wav).joinToString("") { "%02x".format(it) })
        m.conversations.save(conv, m.learner, "es", "OPI", null, 0, StoredConversation(listOf(Turn(Speaker.PARTNER, "¿Hola?"), Turn(Speaker.LEARNER, "Hola"))), null, listOf("1"), "recordings/$conv")
    }

    private val packs = Bundle.PacksList(listOf("es"), listOf("es_ES-davefx-medium"), listOf("eurollm-9b-instruct-q4km"))

    @Test
    fun plainRoundTripIsLosslessAndIdempotent() = roundTrip(null)

    @Test
    fun encryptedRoundTripIsLosslessAndIdempotent() = roundTrip("correct horse battery staple")

    private fun roundTrip(passphrase: String?) {
        val a = Machine("A")
        val b = Machine("B")
        populate(a, 1)
        populate(b, 2)
        val bundle = File(a.dir, "backup.mokuhyo")
        val manifest = a.backup.export(bundle, listOf("es"), packs, passphrase)
        assertEquals(1L, manifest.counts["attempt"])
        assertEquals(passphrase != null, b.backup.isEncrypted(bundle))
        val before = listOf("attempt", "conversation", "recording", "ilr_estimate", "review_item", "review").associateWith { b.count(it) }
        val report = b.backup.importBundle(bundle, passphrase)
        assertEquals(1L, report.added["attempt"])
        assertEquals(1L, report.added["conversation"])
        assertEquals(1L, report.added["recording"])
        assertEquals(1, report.recordingsCopied)
        // Same (lang, kind, ref) review item: merged into B's item, A's review re-keyed onto it.
        assertEquals(0L, report.added["review_item"])
        assertEquals(1L, report.added["review"])
        val item = b.db.srsQueries.itemByRef("B", "es", "WORD", "dict:42").executeAsOne()
        assertEquals(2, b.reviews.card(item.id)!!.reps)
        // Everything A had is now attributed to B (single learner).
        assertEquals(2, b.history.attempts("B", "es").size)
        assertTrue(b.conversations.list("B", "es").size == 2)
        val after = before.mapValues { (t, n) -> b.count(t) - n }
        assertEquals(mapOf("attempt" to 1L, "conversation" to 1L, "recording" to 1L, "ilr_estimate" to 1L, "review_item" to 0L, "review" to 1L), after)
        // Idempotent: importing again changes nothing.
        val again = b.backup.importBundle(bundle, passphrase)
        assertEquals(0L, again.total)
        assertEquals(0, again.recordingsCopied)
        // Recording bytes arrived intact.
        val rec = b.db.historyQueries.recordingsAll().executeAsList().first { r -> a.db.historyQueries.recordingsAll().executeAsList().any { it.id == r.id } }
        assertTrue(File(b.dir, rec.path).readBytes().contentEquals(File(a.dir, rec.path).readBytes()))
    }

    @Test
    fun tombstonesPropagateAndNothingIsOverwritten() {
        val a = Machine("A")
        val b = Machine("B")
        populate(a, 3)
        val bundle = File(a.dir, "b1.mokuhyo")
        a.backup.export(bundle, listOf("es"), packs)
        b.backup.importBundle(bundle)
        val attemptId = a.history.attempts("A", "es").single().id
        a.history.tombstoneAttempt(attemptId)
        val bundle2 = File(a.dir, "b2.mokuhyo")
        a.backup.export(bundle2, listOf("es"), packs)
        val report = b.backup.importBundle(bundle2)
        assertTrue(report.tombstones >= 1)
        assertTrue(b.history.attempts("B", "es").none { it.id == attemptId })
        assertEquals(1L, b.count("attempt"), "the row stays, tombstoned")
    }

    @Test
    fun wrongPassphraseAndTamperingAreRejected() {
        val a = Machine("A")
        populate(a, 4)
        val bundle = File(a.dir, "enc.mokuhyo")
        a.backup.export(bundle, listOf("es"), packs, "secret")
        val b = Machine("B")
        assertFailsWith<IllegalArgumentException> { b.backup.importBundle(bundle, "wrong") }
        assertFailsWith<IllegalArgumentException> { b.backup.importBundle(bundle, null) }
        val bytes = bundle.readBytes()
        bytes[bytes.size - 40] = (bytes[bytes.size - 40].toInt() xor 1).toByte()
        val tampered = File(a.dir, "tampered.mokuhyo").apply { writeBytes(bytes) }
        assertFailsWith<IllegalArgumentException> { b.backup.importBundle(tampered, "secret") }
        // Truncation: drop the final chunk.
        val truncated = File(a.dir, "trunc.mokuhyo").apply { writeBytes(bundle.readBytes().copyOf(bundle.length().toInt() - 10)) }
        assertTrue(runCatching { b.backup.importBundle(truncated, "secret") }.isFailure)
        assertEquals(0L, b.count("attempt"))
    }

    @Test
    fun settingsConflictsAreReportedNotApplied() {
        val a = Machine("A")
        val b = Machine("B")
        a.db.settingsQueries.put("learner.language", "ar", "learner")
        a.db.settingsQueries.put("learner.theme", "dark", "learner")
        a.db.settingsQueries.put("device.model.tier", "D", "device")
        val bundle = File(a.dir, "s.mokuhyo")
        a.backup.export(bundle, listOf("ar"), packs)
        val report = b.backup.importBundle(bundle)
        assertEquals(listOf(Triple("learner.language", "es", "ar")), report.settingConflicts)
        assertEquals("es", b.db.settingsQueries.get("learner.language").executeAsOne())
        assertEquals("dark", b.db.settingsQueries.get("learner.theme").executeAsOne())
        assertEquals(null, b.db.settingsQueries.get("device.model.tier").executeAsOneOrNull(), "device settings never travel")
    }

    @Test
    fun notABundleIsRefused() {
        val b = Machine("B")
        val junk = File(b.dir, "x.mokuhyo").apply { writeText("hello") }
        assertFailsWith<IllegalArgumentException> { b.backup.importBundle(junk) }
    }
}
