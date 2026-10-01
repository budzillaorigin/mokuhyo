package app.mokuhyo.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.exam.Skill
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant

@Serializable
private data class Verdict(val language: String, val kind: String, val id: String, val verdict: String, val note: String = "")

@Serializable
private data class ReviewExport(val format: String = "mokuhyo-review/1", val reviewer: String, val created: String, val verdicts: List<Verdict>)

/**
 * Content Review (developer tool, BRIEF §7): read unreviewed AI-drafted passages and interview questions, mark them
 * accept or reject, and export the verdicts for `tools/items/review.py ingest`, which flips `verified` in the source
 * banks. Nothing here changes the installed packs.
 */
@Composable
fun ContentReviewScreen(app: AppGraph) {
    val lang by app.language.collectAsState()
    var kind by remember { mutableStateOf("exam") }
    var reviewer by remember { mutableStateOf(System.getProperty("user.name") ?: "") }
    val verdicts = remember(lang) { mutableStateMapOf<String, Verdict>() }
    var index by remember(lang, kind) { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    val content = remember(lang) { app.exam(lang) }
    val opi = remember(lang) { app.opi(lang) }
    val units: List<Pair<String, String>> = remember(lang, kind) {
        if (kind == "exam") {
            content?.passages?.values?.filter { !it.verified }?.sortedBy { it.id }?.map { p ->
                val items = content.items.filter { it.passageId == p.id }
                p.id to buildString {
                    appendLine("${p.id} · ${if (p.exam == Skill.READING.exam) "Reading" else "Listening"} · ILR ${p.level} · ${p.textType}")
                    appendLine(p.title)
                    appendLine()
                    appendLine(if (p.script.isNotEmpty()) p.script.joinToString("\n") { "${it.speaker} (${it.voice}): ${it.text}" } else p.body)
                    items.forEach { i ->
                        appendLine()
                        appendLine("[${i.type}] ${i.stem}")
                        i.choices.forEachIndexed { n, c -> appendLine("${if (n == i.answer) "✓" else " "} ${n + 1}. $c") }
                        if (i.explanation.isNotBlank()) appendLine("   ${i.explanation}")
                    }
                }
            }.orEmpty()
        } else {
            opi?.let { o ->
                o.questions.filter { !it.verified }.map { it.id to "${it.id} · ${it.phase} · ILR ${it.level}\n\n${it.prompt}\n${it.english}" } +
                    o.rolePlays.filter { !it.verified }.map { it.id to "${it.id} · role-play · ILR ${it.level}\n\n${it.situation}\nInterviewer: ${it.interviewerRole}\n\n${it.opening}\n${it.english}" } +
                    o.topics.filter { !it.verified }.map { it.id to "${it.id} · topic · ${it.domain}\n\n${it.title}\n${it.opener}" }
            }.orEmpty()
        }
    }
    Page("Content review", "Developer tool · AI-drafted content stays badged until reviewed") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(kind == "exam", { kind = "exam" }, label = { Text("Reading & listening") })
            FilterChip(kind == "opi", { kind = "opi" }, label = { Text("Interview & topics") })
        }
        OutlinedTextField(reviewer, { reviewer = it }, label = { Text("Reviewer") }, singleLine = true)
        Text("${verdicts.size} verdicts this session · ${units.size} unreviewed")
        val unit = units.getOrNull(index)
        if (unit == null) {
            EmptyState("Nothing to review", "Every item for this language and kind has a verdict or is verified.")
        } else {
            SectionCard {
                Text(unit.second, fontFamily = Fonts.forLanguage(lang))
                verdicts[unit.first]?.let { Text("Verdict: ${it.verdict}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary) }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (why rejected, what to fix)") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { verdicts[unit.first] = Verdict(lang, kind, unit.first, "accept", note); note = ""; index++ }) { Text("Accept") }
                    OutlinedButton(onClick = { verdicts[unit.first] = Verdict(lang, kind, unit.first, "reject", note); note = ""; index++ }) { Text("Reject") }
                    OutlinedButton(onClick = { index++ }) { Text("Skip") }
                    OutlinedButton(enabled = index > 0, onClick = { index-- }) { Text("Back") }
                }
            }
        }
        Button(enabled = verdicts.isNotEmpty() && reviewer.isNotBlank(), onClick = {
            val d = FileDialog(null as Frame?, "Export review verdicts", FileDialog.SAVE).apply { file = "mokuhyo-review-$lang.json" }
            d.isVisible = true
            val name = d.file ?: return@Button
            val out = File(d.directory, name)
            out.writeText(Json { prettyPrint = true }.encodeToString(ReviewExport.serializer(), ReviewExport(reviewer = reviewer, created = Instant.now().toString(), verdicts = verdicts.values.toList())))
            status = "Exported ${verdicts.size} verdicts to ${out.name}. Apply them with: uv run python items/review.py ingest ${out.absolutePath}"
        }) { Text("Export verdicts…") }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
