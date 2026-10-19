package app.mokuhyo.history

import app.mokuhyo.backup.Backup
import app.mokuhyo.backup.Bundle
import app.mokuhyo.db.Conversation
import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.opi.OpiRating
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.SpeakingTrend
import app.mokuhyo.opi.Turn
import app.mokuhyo.report.ProgressReport
import app.mokuhyo.report.ReportBuilder
import app.mokuhyo.srs.ReviewService
import app.mokuhyo.testing.repoFile
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QC follow-up F4: an interview's speaking label says the same thing in History and in the PDF entry, provisional or
 * left out of the trend, for new records (stored context) and older ones (estimate row only).
 */
class SpeakingLabelTest {
    private class Machine(val dir: File, val db: MokuhyoDatabase) {
        val conversations = ConversationRepository(db)
        val history = HistoryRepository(db)
        val backup = Backup(File(dir, "db/mokuhyo.sqlite"), db, dir, "L", "test")
    }

    private fun machine(): Machine {
        val dir = Files.createTempDirectory("mokuhyo-f4").toFile()
        val (_, db) = DatabaseFactory.open(File(dir, "db/mokuhyo.sqlite"))
        db.userQueries.insertLearner("L", "L", 0)
        return Machine(dir, db)
    }

    private val transcript = listOf(Turn(Speaker.PARTNER, "¿Qué hiciste ayer?"), Turn(Speaker.LEARNER, "Fui al mercado."))
    private val rating = OpiRating("2", engine = "eurollm-9b", nextSteps = listOf("Narrate in the past."))

    /** What History's row shows for [id] (the row is "<n> answers · <label>"). */
    private fun historyLabel(m: Machine, id: String): String? =
        m.conversations.speakingLabel(assertNotNull(m.conversations.get(id)), m.history.speakingRows("L", "es"))?.text

    /** The PDF's text, or null when the report fonts aren't fetched (then only the label is checked). */
    private fun pdfText(m: Machine): String? {
        val fonts = runCatching { repoFile("tools/.cache/fonts") }.getOrNull()?.takeIf { File(it, "NotoSans-wdth-wght.ttf").isFile }
        if (fonts == null) {
            if (System.getenv("MOKUHYO_REQUIRE_PACKS") == "1") throw AssertionError("fonts missing: run tools/release/stage_fonts.py --cache-only")
            println("SKIP PDF part: fonts not fetched")
            return null
        }
        val data = ReportBuilder(m.db, m.history, m.conversations, ReviewService(m.db)).build("L", "Test Learner", listOf("es"), null, null, false, "test")
        val out = File(m.dir, "report.pdf")
        ProgressReport(fonts).render(data, out)
        return Loader.loadPDF(out).use { PDFTextStripper().getText(it) }
    }

    private fun interviewLine(pdf: String): String = pdf.lines().first { "Interview test" in it }

    /** The report's case: a model-rated test saved with a provisional speaking estimate (confidence 0.3). */
    @Test
    fun provisionalModelRatedTestIsQualifiedInHistoryAndThePdf() {
        val m = machine()
        val id = m.conversations.newId()
        m.conversations.save(id, "L", "es", "OPI_TEST", null, 0,
            StoredConversation(transcript, spokenAnswers = 1, typedAnswers = 0, textFallback = false, calibrated = false,
                trend = SpeakingTrend.Status.COUNTED_PROVISIONAL, trendReason = "not calibrated"),
            rating, listOf("2"), null)
        m.history.saveSpeakingEstimate("L", "es", id, "2", 0.3, provisional = true)
        assertEquals("ILR 2* (AI-rated, provisional — not calibrated)", historyLabel(m, id))
        pdfText(m)?.let { pdf ->
            assertTrue("ILR 2* (AI-rated, provisional — not calibrated)" in interviewLine(pdf), pdf)
            assertTrue(pdf.lines().count { it.startsWith("* provisional") } >= 2, "the footnote is on the cover and with the entry:\n$pdf")
        }
    }

    @Test
    fun excludedMostlyTypedTestSaysItIsNotInTheTrend() {
        val m = machine()
        val id = m.conversations.newId()
        m.conversations.save(id, "L", "es", "OPI_TEST", null, 0,
            StoredConversation(transcript, spokenAnswers = 0, typedAnswers = 1, textFallback = false, calibrated = false,
                trend = SpeakingTrend.Status.EXCLUDED, trendReason = "mostly typed"),
            rating, listOf("2"), null)
        assertTrue(m.history.estimates("L", "es").none { it.modality == "SPEAKING" })
        assertEquals("ILR 2 (AI-rated, not in speaking trend — mostly typed)", historyLabel(m, id))
        pdfText(m)?.let { pdf -> assertTrue("not in speaking trend — mostly typed" in interviewLine(pdf), pdf) }
    }

    /** BRIEF_INTERVIEW_SPEED N5: a mostly guided interview says so in History and the PDF, through the same label. */
    @Test
    fun aGuidedInterviewIsLabelledInHistoryAndThePdf() {
        val m = machine()
        val id = m.conversations.newId()
        val d = SpeakingTrend.decide(true, true, spoken = 10, typed = 0, textFallback = false, calibrated = true, guided = 7, questions = 10)
        m.conversations.save(id, "L", "es", "OPI_TEST", null, 0,
            StoredConversation(transcript, spokenAnswers = 10, typedAnswers = 0, textFallback = false, calibrated = true, trend = d.status, trendReason = d.reason),
            rating, listOf("2"), null)
        assertEquals("ILR 2 (AI-rated, not in speaking trend — guided interview — scripted questions)", historyLabel(m, id))
        pdfText(m)?.let { pdf -> assertTrue("guided interview — scripted questions" in interviewLine(pdf), pdf) }
    }

    /** Records saved before 2026-10-19 have no stored context: their estimate row (or its absence) decides. */
    @Test
    fun olderRecordsFallBackToTheirEstimateRow() {
        val m = machine()
        val provisional = m.conversations.newId()
        m.conversations.save(provisional, "L", "es", "OPI_TEST", null, 0, StoredConversation(transcript), rating, listOf("2"), null)
        m.history.saveSpeakingEstimate("L", "es", provisional, "2", 0.3, provisional = true)
        val firm = m.conversations.newId()
        m.conversations.save(firm, "L", "es", "OPI_TEST", null, 1, StoredConversation(transcript), rating, listOf("2"), null)
        m.history.saveSpeakingEstimate("L", "es", firm, "2", 0.5, provisional = false)
        val typed = m.conversations.newId()
        m.conversations.save(typed, "L", "es", "OPI_TEST", null, 2, StoredConversation(transcript), rating, listOf("2"), null)
        val practice = m.conversations.newId()
        m.conversations.save(practice, "L", "es", "OPI", null, 3, StoredConversation(transcript), rating, listOf("2"), null)
        val self = m.conversations.newId()
        m.conversations.save(self, "L", "es", "OPI_TEST", null, 4, StoredConversation(transcript), OpiRating("1+", selfRated = true), listOf("1+"), null)

        assertEquals("ILR 2* (AI-rated, provisional)", historyLabel(m, provisional))
        assertEquals("ILR 2 (AI-rated)", historyLabel(m, firm))
        assertEquals("ILR 2 (AI-rated, not in speaking trend)", historyLabel(m, typed))
        assertEquals("ILR 2 (AI-rated, not in speaking trend — practice)", historyLabel(m, practice))
        assertEquals("ILR 1+ (self-rated)", historyLabel(m, self))
        pdfText(m)?.let { pdf ->
            val entries = pdf.lines().filter { "Interview" in it && "ILR" in it }
            assertEquals(5, entries.size, pdf)
            assertTrue(entries.any { "ILR 2* (AI-rated, provisional)" in it })
            assertTrue(entries.any { "ILR 2 (AI-rated, not in speaking trend)" in it })
        }
    }

    @Test
    fun newFieldsSurviveTheBundleAndAnOldBundleStillImports() {
        val a = machine()
        val id = a.conversations.newId()
        val stored = StoredConversation(transcript, spokenAnswers = 3, typedAnswers = 1, textFallback = false, calibrated = false,
            trend = SpeakingTrend.Status.COUNTED_PROVISIONAL, trendReason = "not calibrated")
        a.conversations.save(id, "L", "es", "OPI_TEST", null, 0, stored, rating, listOf("2"), null)
        // A conversation written by an app from before these fields existed: its JSON has none of the keys.
        val old = a.conversations.newId()
        a.db.historyQueries.upsertConversation(Conversation(old, "L", "es", "OPI_TEST", null, 1, 2,
            """{"transcript":[{"speaker":"LEARNER","text":"Hola"}]}""", null, "[]", null, null, null, null))
        val bundle = File(a.dir, "x.mokuhyo")
        a.backup.export(bundle, listOf("es"), Bundle.PacksList(emptyList(), emptyList(), emptyList()))

        val b = machine()
        b.backup.importBundle(bundle)
        assertEquals(stored, b.conversations.stored(assertNotNull(b.conversations.get(id))))
        val legacy = b.conversations.stored(assertNotNull(b.conversations.get(old)))
        assertEquals(null, legacy.trend)
        assertEquals(null, legacy.spokenAnswers)
        assertEquals("Hola", legacy.transcript.single().text)
    }
}
