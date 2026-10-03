package app.mokuhyo.report

import com.ibm.icu.text.ArabicShaping
import com.ibm.icu.text.Bidi
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Everything the PDF shows (BRIEF §8.3); assembled from the database by [ReportBuilder]. */
data class ReportData(
    val learnerName: String,
    val generatedAt: Instant,
    val from: Instant?,
    val to: Instant?,
    val appVersion: String,
    val languages: List<LanguageSection>,
    val includeTranscripts: Boolean,
) {
    data class Point(val at: Instant, val level: String, val provisional: Boolean)

    data class AttemptRow(val at: Instant, val modality: String, val mode: String, val correct: Int, val total: Int, val estimate: String?, val provisional: Boolean)

    data class ConversationRow(
        val at: Instant, val kind: String, val topic: String?, val estimate: String?, val nextSteps: List<String>, val aiRated: Boolean,
        /** BRIEF_PHASE8 C-11: the session's corrections mode and its After Action Brief, when one was made. */
        val mode: String? = null, val aab: app.mokuhyo.opi.AfterActionBrief? = null,
    )

    data class Transcript(val at: Instant, val title: String, val lines: List<Pair<String, String>>)

    data class LanguageSection(
        val code: String,
        val name: String,
        val trends: Map<String, List<Point>>,
        val attempts: List<AttemptRow>,
        val conversations: List<ConversationRow>,
        val weakTextTypes: List<Pair<String, Int>>,
        val weakQuestionTypes: List<Pair<String, Int>>,
        val recurringErrors: List<Pair<String, String>>,
        val reviewItems: Long,
        val reviewsDue: Long,
        val reviewsDone: Long,
        val transcripts: List<Transcript> = emptyList(),
    )
}

/**
 * PDF progress report with PDFBox (BRIEF §8.3): embedded Noto fonts for every launch script (subset), vector ILR
 * trend charts (no raster), test history, interview summaries, weak areas, review stats. Arabic and Persian are
 * shaped (presentation forms) and reordered right-to-left with ICU. PDF only (no HTML export anywhere in the app).
 */
class ProgressReport(private val fontsDir: File) {
    private val dateFmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault())

    private val families = listOf(
        "latin" to "NotoSans-wdth-wght.ttf", "ja" to "NotoSansJP-wght.ttf", "zh" to "NotoSansSC-wght.ttf",
        "ko" to "NotoSansKR-wght.ttf", "ar" to "NotoNaskhArabic-wght.ttf",
    )

    fun render(data: ReportData, out: File, progress: (Double) -> Unit = {}) {
        PDDocument().use { doc ->
            val fonts = families.associate { (k, f) -> k to PDType0Font.load(doc, File(fontsDir, f)) }
            val w = Writer(doc, fonts)
            cover(w, data)
            data.languages.forEachIndexed { i, s ->
                progress(i.toDouble() / data.languages.size.coerceAtLeast(1))
                section(w, s, data)
            }
            w.finish()
            out.parentFile?.mkdirs()
            doc.save(out)
            progress(1.0)
        }
    }

    private fun cover(w: Writer, d: ReportData) {
        w.newPage()
        w.text("Mokuhyo progress report", 24f, bold = true)
        w.gap(8f)
        w.text(d.learnerName, 16f)
        w.text("Languages: " + d.languages.joinToString(", ") { it.name }, 12f)
        val range = listOfNotNull(d.from?.let(dateFmt::format), d.to?.let(dateFmt::format)).joinToString(" – ").ifEmpty { "all dates" }
        w.text("Period: $range · generated ${dateFmt.format(d.generatedAt)} · app ${d.appVersion}", 10f, gray = true)
        w.gap(16f)
        w.box(
            "Unofficial practice — not an official rating. The levels in this report are estimates from practice tests and " +
                "simulated interviews in the Mokuhyo app. They are not DLPT or OPI scores and are not affiliated with DLI, ACTFL, " +
                "AFCLC or the LEAP program.",
        )
        w.gap(16f)
        d.languages.forEach { s ->
            val latest = s.trends.mapValues { it.value.lastOrNull()?.level ?: "—" }
            w.text("${s.name}: Reading ${latest["READING"] ?: "—"} · Listening ${latest["LISTENING"] ?: "—"} · Speaking ${latest["SPEAKING"] ?: "—"}", 12f)
        }
    }

    private fun section(w: Writer, s: ReportData.LanguageSection, d: ReportData) {
        w.newPage()
        w.text("${s.name} (${s.code})", 18f, bold = true)
        w.gap(6f)
        w.text("ILR estimates over time", 13f, bold = true)
        w.chart(s.trends)
        w.gap(8f)
        w.text("Tests", 13f, bold = true)
        val tests = s.attempts.filter { it.mode == "TEST" }
        if (tests.isEmpty()) w.text("No tests in this period.", 10f, gray = true)
        w.table(
            listOf("Date", "Skill", "Score", "Estimate"), listOf(110f, 110f, 90f, 160f),
            tests.map { a -> listOf(dateFmt.format(a.at), a.modality.lowercase().replaceFirstChar { it.uppercase() }, "${a.correct}/${a.total}", (a.estimate?.let { "ILR $it" } ?: "—") + if (a.provisional) " (provisional)" else "") },
        )
        val practice = s.attempts.count { it.mode == "PRACTICE" }
        if (practice > 0) w.text("$practice practice sets (not counted in the estimates).", 10f, gray = true)
        w.gap(8f)
        w.text("Interviews and conversations", 13f, bold = true)
        if (s.conversations.isEmpty()) w.text("None in this period.", 10f, gray = true)
        s.conversations.forEach { c ->
            val kind = when (c.kind) { "OPI_TEST" -> "Interview test"; "OPI" -> "Interview practice"; "SCENARIO" -> "Scenario"; "INTERPRET" -> "Interpretation"; else -> "Conversation" }
            w.text("${dateFmt.format(c.at)} · $kind${c.topic?.let { " · $it" } ?: ""}${c.estimate?.let { " · ILR $it" } ?: ""}${if (c.aiRated) " (AI-rated)" else ""}", 10.5f)
            c.nextSteps.forEach { w.text("   • $it", 9.5f, gray = true) }
            c.aab?.let { a ->
                w.text("   After Action Brief (${c.mode?.replace('_', ' ') ?: "after action"}): ${a.turns} turns, ${a.durationSec / 60} min" +
                    (a.persona?.let { ", with $it" } ?: ""), 9.5f)
                a.nextSteps.forEach { w.text("      → $it", 9.5f, gray = true) }
                a.patterns.grammar.take(3).forEach { w.text("      Grammar: $it", 9.5f, gray = true, lang = s.code) }
                a.patterns.register.take(2).forEach { w.text("      Register: $it", 9.5f, gray = true, lang = s.code) }
                if (a.cultural.isNotEmpty()) w.text("      Cultural notes (not part of the ILR scale): " +
                    a.cultural.joinToString(", ") { "${it.tag} ${it.flags.size}" }, 9.5f, gray = true)
            }
        }
        w.gap(8f)
        w.text("Weak areas", 13f, bold = true)
        if (s.weakTextTypes.isEmpty() && s.weakQuestionTypes.isEmpty() && s.recurringErrors.isEmpty()) w.text("Nothing stands out yet.", 10f, gray = true)
        if (s.weakTextTypes.isNotEmpty()) w.text("Text types: " + s.weakTextTypes.joinToString(", ") { "${it.first.replace('_', ' ')} (${it.second}% right)" }, 10.5f)
        if (s.weakQuestionTypes.isNotEmpty()) w.text("Question types: " + s.weakQuestionTypes.joinToString(", ") { "${it.first.replace('_', ' ')} (${it.second}% right)" }, 10.5f)
        s.recurringErrors.take(8).forEach { (from, to) -> w.text("Recurring: $from → $to", 10.5f, lang = s.code) }
        w.gap(8f)
        w.text("Review queue", 13f, bold = true)
        w.text("${s.reviewItems} items · ${s.reviewsDue} due now · ${s.reviewsDone} reviews in this period", 10.5f)
        if (d.includeTranscripts && s.transcripts.isNotEmpty()) {
            w.gap(10f)
            w.text("Transcripts", 13f, bold = true)
            s.transcripts.forEach { t ->
                w.text("${dateFmt.format(t.at)} · ${t.title}", 11f, bold = true)
                t.lines.forEach { (who, line) -> w.text("$who: $line", 10f, lang = s.code) }
                w.gap(4f)
            }
        }
    }

    /** A tiny flowing layout: wraps text, breaks pages, picks a font per character, shapes Arabic script. */
    private inner class Writer(private val doc: PDDocument, private val fonts: Map<String, PDType0Font>) {
        private val page = PDRectangle.A4
        private val margin = 50f
        private var cs: PDPageContentStream? = null
        private var y = 0f
        private var pageNo = 0
        private val width = page.width - 2 * margin

        fun newPage() {
            cs?.close()
            val p = PDPage(page)
            doc.addPage(p)
            cs = PDPageContentStream(doc, p)
            pageNo++
            y = page.height - margin
            footer()
        }

        private fun footer() {
            draw("Mokuhyo · unofficial practice report · page $pageNo", margin, 28f, 8f, true, null)
        }

        fun finish() {
            cs?.close()
            cs = null
        }

        fun gap(h: Float) {
            y -= h
        }

        private fun ensure(h: Float) {
            if (y - h < margin + 20) newPage()
        }

        fun text(s: String, size: Float, bold: Boolean = false, gray: Boolean = false, lang: String? = null) {
            for (line in wrap(s, size, lang)) {
                ensure(size * 1.45f)
                y -= size * 1.45f
                val rtl = rtlParagraph(line)
                val x = if (rtl) margin + width - measure(visual(line), size, lang) else margin
                draw(line, x, y, size * (if (bold) 1.02f else 1f), gray, lang)
            }
        }

        fun box(s: String) {
            text(s, 10.5f, gray = false)
        }

        fun table(headers: List<String>, widths: List<Float>, rows: List<List<String>>) {
            if (rows.isEmpty()) return
            val size = 9.5f
            ensure(size * 2)
            y -= size * 1.6f
            var x = margin
            headers.forEachIndexed { i, h -> draw(h, x, y, size, true, null); x += widths[i] }
            rows.forEach { r ->
                ensure(size * 1.5f)
                y -= size * 1.5f
                x = margin
                r.forEachIndexed { i, c -> draw(c, x, y, size, false, null); x += widths[i] }
            }
        }

        /** ILR trend per modality as vector polylines on a discrete level axis. */
        fun chart(trends: Map<String, List<ReportData.Point>>) {
            val h = 150f
            ensure(h + 30f)
            val top = y - 10f
            val bottom = top - h
            val left = margin + 40f
            val right = margin + width - 10f
            val levels = listOf("0+", "1", "1+", "2", "2+", "3")
            val c = cs!!
            c.setLineWidth(0.4f)
            c.setStrokingColor(0.8f, 0.8f, 0.8f)
            levels.forEachIndexed { i, lv ->
                val yy = bottom + h * i / (levels.size - 1)
                c.moveTo(left, yy); c.lineTo(right, yy); c.stroke()
                draw(lv, margin + 10f, yy - 3f, 8f, true, null)
            }
            val all = trends.values.flatten()
            if (all.isEmpty()) {
                draw("No estimates yet.", left + 10f, bottom + h / 2, 10f, true, null)
            } else {
                val t0 = all.minOf { it.at.toEpochMilli() }
                val t1 = maxOf(all.maxOf { it.at.toEpochMilli() }, t0 + 86_400_000L)
                val colors = mapOf("READING" to floatArrayOf(0.11f, 0.17f, 0.29f), "LISTENING" to floatArrayOf(0.84f, 0.27f, 0.2f), "SPEAKING" to floatArrayOf(0.2f, 0.55f, 0.35f))
                var legendX = left
                trends.forEach { (modality, pts) ->
                    if (pts.isEmpty()) return@forEach
                    val col = colors[modality] ?: floatArrayOf(0.3f, 0.3f, 0.3f)
                    c.setStrokingColor(col[0], col[1], col[2])
                    c.setNonStrokingColor(col[0], col[1], col[2])
                    c.setLineWidth(1.6f)
                    val xy = pts.map { p ->
                        val xx = left + (right - left) * (p.at.toEpochMilli() - t0) / (t1 - t0).toFloat()
                        val yy = bottom + h * levels.indexOf(p.level).coerceAtLeast(0) / (levels.size - 1)
                        xx to yy
                    }
                    xy.forEachIndexed { i, (xx, yy) -> if (i == 0) c.moveTo(xx, yy) else c.lineTo(xx, yy) }
                    c.stroke()
                    xy.forEach { (xx, yy) -> c.addRect(xx - 2f, yy - 2f, 4f, 4f); c.fill() }
                    c.addRect(legendX, bottom - 16f, 10f, 4f); c.fill()
                    draw(modality.lowercase().replaceFirstChar { it.uppercase() }, legendX + 14f, bottom - 18f, 8f, false, null)
                    legendX += 90f
                }
                c.setNonStrokingColor(0f, 0f, 0f)
                c.setStrokingColor(0f, 0f, 0f)
            }
            y = bottom - 26f
        }

        private fun wrap(s: String, size: Float, lang: String?): List<String> {
            val out = ArrayList<String>()
            for (para in s.split('\n')) {
                val noSpaces = para.any { it in '぀'..'ヿ' || it in '一'..'鿿' }
                val units = if (noSpaces) para.map { it.toString() } else para.split(' ').map { "$it " }
                var line = StringBuilder()
                for (u in units) {
                    if (line.isNotEmpty() && measure(line.toString() + u, size, lang) > width) {
                        out += line.toString().trimEnd()
                        line = StringBuilder()
                    }
                    line.append(u)
                }
                out += line.toString().trimEnd()
            }
            return out
        }

        /** Visual order with Arabic shaping (presentation forms) for right-to-left runs. */
        private fun visual(s: String): String {
            if (s.none { it in '؀'..'ۿ' }) return s
            val shaped = ArabicShaping(ArabicShaping.LETTERS_SHAPE or ArabicShaping.TEXT_DIRECTION_LOGICAL).shape(s)
            val base = if (rtlParagraph(s)) Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT else Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
            return Bidi(shaped, base.toInt()).writeReordered(Bidi.DO_MIRRORING.toInt())
        }

        /** Paragraph direction from the first strong character (Unicode rule P2). */
        private fun rtlParagraph(s: String): Boolean {
            for (c in s) {
                when (Character.getDirectionality(c)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                }
            }
            return false
        }

        private fun fontFor(cp: Int, lang: String?): PDType0Font {
            // Latin, punctuation and digits always use Noto Sans, whatever the language.
            if (cp < 0x0250 || cp in 0x2000..0x206F || cp in 0x2190..0x21FF) fonts.getValue("latin").takeIf { has(it, cp) }?.let { return it }
            val order = when (lang) {
                "ja" -> listOf("ja", "latin", "zh", "ko", "ar")
                "zh-Hans" -> listOf("zh", "latin", "ja", "ko", "ar")
                "ko" -> listOf("ko", "latin", "ja", "zh", "ar")
                "ar", "fa" -> listOf("ar", "latin", "ja", "zh", "ko")
                else -> listOf("latin", "ja", "zh", "ko", "ar")
            }
            return order.map { fonts.getValue(it) }.firstOrNull { has(it, cp) } ?: fonts.getValue("latin")
        }

        private fun has(f: PDType0Font, cp: Int): Boolean = runCatching { f.encode(String(Character.toChars(cp))); true }.getOrDefault(false)

        private fun runs(s: String, lang: String?): List<Pair<PDType0Font, String>> {
            val out = ArrayList<Pair<PDType0Font, String>>()
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                val f = fontFor(cp, lang)
                val ch = String(Character.toChars(cp))
                if (out.isNotEmpty() && out.last().first === f) out[out.lastIndex] = f to out.last().second + ch else out += f to ch
                i += Character.charCount(cp)
            }
            return out
        }

        private fun measure(s: String, size: Float, lang: String?): Float =
            runs(visual(s), lang).sumOf { (f, t) -> (runCatching { f.getStringWidth(t) }.getOrDefault(500f * t.length) / 1000f * size).toDouble() }.toFloat()

        private fun draw(s: String, x: Float, yy: Float, size: Float, gray: Boolean, lang: String?) {
            val c = cs ?: return
            val visualText = visual(s)
            var xx = x
            c.setNonStrokingColor(if (gray) 0.4f else 0f, if (gray) 0.4f else 0f, if (gray) 0.4f else 0f)
            for ((f, t) in runs(visualText, lang)) {
                c.beginText()
                c.setFont(f, size)
                c.newLineAtOffset(xx, yy)
                c.showText(t)
                c.endText()
                xx += f.getStringWidth(t) / 1000f * size
            }
            c.setNonStrokingColor(0f, 0f, 0f)
        }
    }
}
