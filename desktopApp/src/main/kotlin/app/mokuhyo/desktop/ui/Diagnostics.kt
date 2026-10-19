package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.Timings
import app.mokuhyo.speech.TimingReport
import app.mokuhyo.speech.TurnTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate

/** The columns of the per-turn table, in pipeline order. */
internal val DIAGNOSTIC_COLUMNS = listOf(TurnTimeline.FIRST_AUDIO, "endpoint", "asr", "llm_queue", "llm_first_token", "llm_validated", "tts_first_pcm", "output_open")

/** One row per turn of the last session: stage milliseconds, "—" where a stage didn't run. */
internal fun diagnosticRows(report: TimingReport): List<List<String>> = report.sessions.flatMap { s ->
    s.turns.map { t ->
        listOf("${t.index}${if (t.cold || t.index == 0) " (cold)" else ""}") +
            DIAGNOSTIC_COLUMNS.map { c -> t.stages[c]?.toString() ?: "—" } +
            listOf(if (t.prerendered) "prerendered" else t.fallback.takeIf { it != "none" } ?: "")
    }
}

/**
 * Settings → Speech & audio → Diagnostics (BRIEF_INTERVIEW_SPEED N1): the last spoken session's timeline, and an
 * export of it for an issue. Timings only: the report has no transcript text and no audio.
 */
@Composable
fun DiagnosticsSection(app: AppGraph) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<TimingReport?>(null) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { app.timings.last() } }
    SectionCard("Diagnostics: interview timing") {
        Text("How long each step of your last spoken session took, in milliseconds. \"first_audio\" is from the end of your speech to " +
            "the partner's first audible words. Only timings are recorded — never what was said.", style = MaterialTheme.typography.bodySmall)
        val r = report
        if (r == null || r.sessions.all { it.turns.isEmpty() }) {
            Text("No spoken session has been timed on this computer yet.")
        } else {
            val s = r.sessions.first()
            Text("${s.kind} · ${s.language} · ${listOfNotNull(s.modelId, s.backend, s.voiceId).joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
            val header = listOf("turn") + DIAGNOSTIC_COLUMNS + listOf("fallback")
            (listOf(header) + diagnosticRows(r)).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { i, cell ->
                        Text(cell, Modifier.width(if (i == 0) 72.dp else 88.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            r.summary.warm[TurnTimeline.FIRST_AUDIO]?.let { Text("Warm turns: first audio p50 ${it.p50} ms, p95 ${it.p95} ms (n=${it.n}).") }
            r.summary.fallbackRate?.let { Text("Scripted or fallback questions: ${(it * 100).toInt()} %.", style = MaterialTheme.typography.bodySmall) }
        }
        Row {
            OutlinedButton(enabled = r != null, onClick = {
                val d = FileDialog(null as Frame?, "Export timing report", FileDialog.SAVE).apply { file = "mokuhyo-timing-${LocalDate.now()}.json" }
                d.isVisible = true
                val name = d.file ?: return@OutlinedButton
                val base = File(d.directory, name.removeSuffix(".json").removeSuffix(".md"))
                scope.launch {
                    status = runCatching { withContext(Dispatchers.IO) { Timings.export(r!!, base) } }
                        .fold({ files -> "Saved ${files.joinToString { it.name }}. Attach both to an issue (timings only, no speech)." }, { "Couldn't save: ${it.message}" })
                }
            }) { Text("Export timing report…") }
            OutlinedButton(onClick = { scope.launch { report = withContext(Dispatchers.IO) { app.timings.last() } } }) { Text("Refresh") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Settings → Speech & audio → Readiness by language (BRIEF_INTERVIEW_SPEED N4): for each launch language, the live voice
 * and where it comes from, speech recognition, the model, and the last measured reply speed on this computer.
 */
@Composable
fun ReadinessSection(app: AppGraph) {
    var rows by remember { mutableStateOf<List<app.mokuhyo.speech.LanguageReadiness>?>(null) }
    LaunchedEffect(Unit) { rows = withContext(Dispatchers.IO) { app.readiness() } }
    SectionCard("Readiness for spoken interviews") {
        val all = rows
        if (all == null) Text("Checking voices and models…")
        else all.forEach { r -> ReadinessRow(r) }
    }
}

@Composable
fun ReadinessRow(r: app.mokuhyo.speech.LanguageReadiness) {
    Text(r.name + if (r.spoken) "" else " — spoken interview not available", style = MaterialTheme.typography.titleSmall,
        color = if (r.spoken) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
    r.lines().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
}
