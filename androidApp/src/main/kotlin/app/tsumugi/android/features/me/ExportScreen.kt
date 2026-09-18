package app.tsumugi.android.features.me

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.Exports
import app.tsumugi.android.ui.readable
import app.tsumugi.export.StudyReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Me → Export & backup (G-10): reviews CSV and the PDF study report through the share sheet, a JSON backup to a file
 * the learner picks (SAF), and restore, which merges through the sync merge rules (D-116: nothing is ever deleted).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var days by remember { mutableIntStateOf(30) }

    fun run(label: String, block: suspend () -> String?) {
        status = context.getString(R.string.status_working, label)
        failure = null
        progress = null
        job = scope.launch {
            try {
                status = block()
                retry = null
            } catch (e: CancellationException) {
                status = null
                throw e
            } catch (e: Exception) {
                status = null
                failure = context.getString(R.string.status_failed, label, e.readable())
                retry = { run(label, block) }
            } finally {
                progress = null
                job = null
            }
        }
    }
    val today = java.time.LocalDate.now().toString()

    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run(context.getString(R.string.export_backup_working)) {
            val json = graph.backup.exportJson()
            Exports.writeText(context, uri, json)
            context.getString(R.string.export_backup_done)
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run(context.getString(R.string.export_restore_working)) {
            val file = File(context.cacheDir, "restore.json")
            try {
                Exports.copy(context, uri, file)
                val r = graph.restoreBackup(file.path) { p -> progress = p.done to p.total }
                context.getString(R.string.export_restore_done, r.applied, r.unchanged)
            } finally {
                file.delete()
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        status?.let { Text(it) }
        progress?.let { (done, total) -> LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth()) }
        if (job != null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
        }
        failure?.let { ErrorState(it, onRetry = { retry?.invoke() }) }

        Text(stringResource(R.string.export_csv_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.export_csv_hint), style = MaterialTheme.typography.bodySmall)
        Button(onClick = {
            run(context.getString(R.string.export_csv_working)) {
                val file = Exports.file(context, "tsumugi-reviews-$today.csv")
                val count = withContext(Dispatchers.IO) {
                    val rows = graph.reviewCsv.rows()
                    file.writeText(graph.reviewCsv.csv(rows), Charsets.UTF_8)
                    rows.size
                }
                if (!Exports.share(context, file, "text/csv", context.getString(R.string.export_share))) context.getString(R.string.export_no_app)
                else context.getString(R.string.export_csv_done, count)
            }
        }, enabled = job == null) { Text(stringResource(R.string.export_csv_button)) }

        HorizontalDivider()
        Text(stringResource(R.string.export_pdf_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(30, 90, 365).forEach { d -> FilterChip(days == d, { days = d }, { Text(stringResource(R.string.export_days, d)) }) }
        }
        Button(onClick = {
            run(context.getString(R.string.export_pdf_working)) {
                val report = graph.studyReport(days)
                val file = Exports.file(context, "tsumugi-report-$today.pdf")
                withContext(Dispatchers.IO) { StudyReportPdf.write(context, report, file) }
                if (!Exports.share(context, file, "application/pdf", context.getString(R.string.export_share))) context.getString(R.string.export_no_app)
                else context.getString(R.string.export_pdf_done)
            }
        }, enabled = job == null) { Text(stringResource(R.string.export_pdf_button)) }

        HorizontalDivider()
        Text(stringResource(R.string.export_backup_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.export_backup_hint), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { backupPicker.launch("tsumugi-backup-$today.json") }, enabled = job == null) { Text(stringResource(R.string.export_backup_button)) }
            OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/json", "*/*")) }, enabled = job == null) { Text(stringResource(R.string.export_restore_button)) }
        }
    }
}

/**
 * Renders the shared [StudyReport] model (D-115) with android.graphics.pdf: the text sections in order, then a bar
 * chart of reviews per day. A4 portrait, paginated.
 */
object StudyReportPdf {
    private const val W = 595
    private const val H = 842
    private const val M = 48f

    fun write(context: Context, report: StudyReport, file: File) {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true }
        val heading = Paint().apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD; isAntiAlias = true; color = Color.rgb(0x5B, 0x3A, 0x8C) }
        val body = Paint().apply { textSize = 11f; isAntiAlias = true }
        val muted = Paint(body).apply { color = Color.DKGRAY; textSize = 9f }
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newPage() {
            page?.let { doc.finishPage(it) }
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            y = M
        }
        fun ensure(space: Float) {
            if (page == null || y + space > H - M) newPage()
        }
        ensure(40f)
        y += 20f
        page!!.canvas.drawText(context.getString(R.string.report_title), M, y, title)
        y += 16f
        page!!.canvas.drawText(context.getString(R.string.report_generated, report.generatedAt.toString().take(16).replace('T', ' ')), M, y, muted)
        y += 18f
        for (section in report.sections) {
            ensure(40f)
            y += 14f
            page!!.canvas.drawText(section.title, M, y, heading)
            y += 6f
            for (row in section.rows) {
                ensure(16f)
                y += 15f
                page!!.canvas.drawText(row.label, M, y, body)
                val w = body.measureText(row.value)
                page!!.canvas.drawText(row.value, W - M - w, y, body)
            }
        }
        if (report.daily.isNotEmpty()) {
            ensure(180f)
            y += 24f
            page!!.canvas.drawText(context.getString(R.string.report_daily), M, y, heading)
            y += 10f
            val chartH = 120f
            val max = (report.daily.maxOf { it.count }).coerceAtLeast(1)
            val barW = (W - 2 * M) / report.daily.size
            val bar = Paint().apply { color = Color.rgb(0x5B, 0x3A, 0x8C) }
            val axis = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
            val base = y + chartH
            page!!.canvas.drawLine(M, base, W - M, base, axis)
            report.daily.forEachIndexed { i, d ->
                val h = chartH * d.count / max
                if (h > 0) page!!.canvas.drawRect(M + i * barW + barW * 0.1f, base - h, M + (i + 1) * barW - barW * 0.1f, base, bar)
            }
            page!!.canvas.drawText(report.daily.first().date.toString(), M, base + 12f, muted)
            val last = report.daily.last().date.toString()
            page!!.canvas.drawText(last, W - M - muted.measureText(last), base + 12f, muted)
            page!!.canvas.drawText("max $max", M, y + 8f, muted)
            y = base + 16f
        }
        page?.let { doc.finishPage(it) }
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
    }
}
