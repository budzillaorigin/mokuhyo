package app.mokuhyo.report

import app.mokuhyo.lang.Languages
import app.mokuhyo.testing.repoFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFStreamEngine
import org.apache.pdfbox.contentstream.operator.state.Concatenate
import org.apache.pdfbox.contentstream.operator.state.Restore
import org.apache.pdfbox.contentstream.operator.state.Save
import org.apache.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import org.apache.pdfbox.contentstream.operator.state.SetMatrix
import org.apache.pdfbox.contentstream.operator.text.BeginText
import org.apache.pdfbox.contentstream.operator.text.EndText
import org.apache.pdfbox.contentstream.operator.text.MoveText
import org.apache.pdfbox.contentstream.operator.text.SetFontAndSize
import org.apache.pdfbox.contentstream.operator.text.ShowText
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** BRIEF §11.1 gate_data: the report renders for a fixture learner in all 11 languages with no .notdef glyphs. */
class ReportFontTest {
    private val samples = Json.parseToJsonElement(javaClass.getResource("/lang/samples.json")!!.readText()).jsonObject

    private class GlyphCheck : PDFStreamEngine() {
        var glyphs = 0
        var notdef = 0

        init {
            listOf(BeginText(this), EndText(this), SetFontAndSize(this), ShowText(this), MoveText(this), Save(this), Restore(this),
                Concatenate(this), SetMatrix(this), SetGraphicsStateParameters(this)).forEach { addOperator(it) }
        }

        override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
            glyphs++
            val gid = (font as? PDType0Font)?.codeToGID(code) ?: code
            if (gid == 0) notdef++
        }
    }

    @Test
    fun allLanguagesRenderWithoutMissingGlyphs() {
        val fonts = runCatching { repoFile("tools/.cache/fonts") }.getOrNull()?.takeIf { File(it, "NotoSansSC-wght.ttf").isFile }
        if (fonts == null) {
            if (System.getenv("MOKUHYO_REQUIRE_PACKS") == "1") throw AssertionError("fonts missing: run tools/release/stage_fonts.py --cache-only")
            return println("SKIP fonts not fetched")
        }
        val now = Instant.parse("2026-09-30T12:00:00Z")
        val sections = Languages.all.map { l ->
            val sentence = samples[l.code]!!.jsonObject["sentence"]!!.jsonPrimitive.content
            ReportData.LanguageSection(
                code = l.code, name = "${l.nameEnglish} ${l.nameNative}",
                trends = mapOf(
                    "READING" to listOf(ReportData.Point(now.minusSeconds(86_400 * 20), "1+", false), ReportData.Point(now, "2", false)),
                    "LISTENING" to listOf(ReportData.Point(now.minusSeconds(86_400 * 10), "1", true)),
                    "SPEAKING" to listOf(ReportData.Point(now, "1+", false)),
                ),
                attempts = listOf(ReportData.AttemptRow(now, "READING", "TEST", 40, 60, "2", false)),
                conversations = listOf(ReportData.ConversationRow(now, "OPI_TEST", sentence.take(20), "1+", listOf("Practise narrating in the past."), true)),
                weakTextTypes = listOf("editorial" to 40), weakQuestionTypes = listOf("inference" to 50),
                recurringErrors = listOf(sentence.take(8) to sentence.takeLast(8)),
                reviewItems = 12, reviewsDue = 3, reviewsDone = 40,
                transcripts = listOf(ReportData.Transcript(now, "Interview", listOf("Interviewer" to sentence, "You" to sentence))),
            )
        }
        val out = System.getenv("MOKUHYO_REPORT_OUT")?.let(::File) ?: Files.createTempFile("report", ".pdf").toFile()
        ProgressReport(fonts).render(ReportData("Test Learner", now, null, now, "test", sections, includeTranscripts = true), out)
        Loader.loadPDF(out).use { doc ->
            assertTrue(doc.numberOfPages >= 12, "cover + one page per language")
            doc.pages.forEachIndexed { i, page ->
                val check = GlyphCheck()
                check.processPage(page)
                assertEquals(0, check.notdef, "page ${i + 1}: ${check.notdef} of ${check.glyphs} glyphs are .notdef")
                assertTrue(check.glyphs > 0, "page ${i + 1} has no text")
            }
            // Vector charts, no images.
            doc.pages.forEach { p -> assertTrue(p.resources.xObjectNames.none(), "raster image found") }
        }
        println("report: ${out.length() / 1024} KB")
    }
}
