package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.BuildInfo
import app.mokuhyo.lang.Languages
import app.mokuhyo.report.ProgressReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private enum class Range(val title: String, val days: Long?) { MONTH("Last 30 days", 30), QUARTER("Last 90 days", 90), YEAR("Last year", 365), ALL("Everything", null) }

/** Report (BRIEF §8.3): a PDF for a mentor. PDF only; transcripts off by default. */
@Composable
fun ReportScreen(app: AppGraph) {
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(Range.QUARTER) }
    val langs = remember { mutableStateListOf<String>().apply { addAll(app.chosenLanguages()) } }
    var transcripts by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    Page("Report", "A PDF of your progress to keep or send to a mentor") {
        SectionCard("Period") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Range.entries.forEach { r -> FilterChip(range == r, { range = r }, label = { Text(r.title) }) } }
        }
        SectionCard("Languages") {
            Languages.all.filter { it.code in app.chosenLanguages() || app.history.attempts(app.learnerId, it.code).isNotEmpty() }.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(l.code in langs, { if (it) langs += l.code else langs -= l.code })
                    Text(l.nameEnglish)
                }
            }
        }
        SectionCard("Options") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(transcripts, { transcripts = it })
                Text("Include interview and conversation transcripts")
            }
        }
        val fonts = app.fontsDir()
        if (fonts == null) Text("The report fonts are missing from this build.", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = progress == null && langs.isNotEmpty() && fonts != null, onClick = {
                val dialog = FileDialog(null as Frame?, "Save report", FileDialog.SAVE).apply { file = "mokuhyo-report-${LocalDate.now()}.pdf" }
                dialog.isVisible = true
                val name = dialog.file ?: return@Button
                val out = File(dialog.directory, if (name.endsWith(".pdf")) name else "$name.pdf")
                progress = 0f
                status = "Building the report…"
                job = scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val from = range.days?.let { Instant.now().minus(it, ChronoUnit.DAYS) }
                            val learner = app.db.userQueries.learner(app.learnerId).executeAsOneOrNull()?.displayName ?: "Learner"
                            val data = app.reportBuilder().build(app.learnerId, learner, langs.toList(), from, Instant.now(), transcripts, BuildInfo.version)
                            ProgressReport(fonts!!).render(data, out) { p -> progress = p.toFloat() }
                        }
                    }
                    status = result.fold({ "Saved ${out.absolutePath}" }, { "Couldn't build the report: ${it.message}" })
                    progress = null
                }
            }) { Text("Save PDF…") }
            if (progress != null) TextButton(onClick = { job?.cancel(); progress = null; status = "Cancelled." }) { Text("Cancel") }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        if (status.isNotEmpty()) Text(status)
        Disclaimer()
    }
}
