package app.tsumugi.android.features.exams

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.tsumugi.android.features.practice.EXAM_DISCLAIMER
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.exam.AttemptSummary
import app.tsumugi.exam.ExamCoverage
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.jlpt.JlptBlueprints
import app.tsumugi.exam.jlpt.JlptItemType
import java.text.DateFormat
import java.util.Date

/** What to assemble; the runner builds the form from this (twice with the same seed: preview, then the real start). */
sealed interface ExamSpec {
    val title: String
    /** Strict modes: real timing, audio once, no lookups. */
    val strict: Boolean

    data class JlptMock(val level: Int) : ExamSpec {
        override val title get() = "JLPT N$level mock"
        override val strict get() = true
    }

    data class JlptSection(val level: Int, val sectionId: String, val sectionTitle: String) : ExamSpec {
        override val title get() = "N$level · $sectionTitle"
        override val strict get() = false
    }

    data class JlptType(val level: Int, val type: String) : ExamSpec {
        override val title get() = "N$level · " + (JlptItemType.of(type)?.english ?: type)
        override val strict get() = false
    }

    data class Dlpt(val exam: ExamKind, val minutes: Int) : ExamSpec {
        override val title get() = "${exam.title} · ${if (minutes >= 180) "full length" else "$minutes min"}"
        override val strict get() = true
    }
}

const val EXAM_PACK_MISSING =
    "The exam pack (JLPT blueprints and item banks) isn't installed in this build. It's built by tools/packs/build_exam.py " +
        "(see docs/CONTENT_PACKS.md). You can also import your own item bank (JSON) from Me → Import & export."

/** Exams hub (BRIEF §5.11): JLPT mock/section/type drills with coverage, DLPT slices, OPI, history. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExamHubScreen(onStart: (ExamSpec) -> Unit, onOpi: () -> Unit, onOpenAttempt: (String) -> Unit, onImport: () -> Unit) {
    val graph = rememberGraph()
    var level by remember { mutableIntStateOf(4) }
    var blueprints by remember { mutableStateOf<JlptBlueprints?>(null) }
    var coverage by remember { mutableStateOf<List<ExamCoverage>>(emptyList()) }
    var history by remember { mutableStateOf<List<AttemptSummary>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val exams = graph.exams()
        blueprints = exams.blueprints()
        coverage = exams.coverage()
        history = exams.history()
        loaded = true
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Notice(EXAM_DISCLAIMER)
        if (!loaded) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        SectionTitle("JLPT")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (5 downTo 1).forEach { l -> FilterChip(level == l, { level = l }, { Text("N$l") }) }
        }
        val bp = blueprints?.level(level)
        val cov = coverage.firstOrNull { it.exam == ExamKind.JLPT && it.level == "N$level" }
        if (bp == null) {
            Notice(EXAM_PACK_MISSING, actionLabel = "Import an item bank", onAction = onImport)
        } else {
            Text("${cov?.total ?: 0} items available · a full mock uses ${bp.itemCount} in ${bp.totalMinutes} minutes", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { onStart(ExamSpec.JlptMock(level)) }, enabled = (cov?.total ?: 0) > 0) { Text("Full mock (${bp.totalMinutes} min)") }
            Text("Section drill", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bp.sections.forEach { s ->
                    val available = s.items.sumOf { cov?.types?.get(it.type) ?: 0 }
                    OutlinedButton(onClick = { onStart(ExamSpec.JlptSection(level, s.id, s.title)) }, enabled = available > 0) {
                        Text("${s.title} · ${s.minutes} min", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text("Item-type drill (available / on a real test)", style = MaterialTheme.typography.titleSmall)
            val specs = bp.sections.flatMap { it.items }.groupBy { it.type }
            specs.forEach { (type, list) ->
                val available = cov?.types?.get(type) ?: 0
                val kind = JlptItemType.of(type)
                ListItem(
                    modifier = Modifier.clickable(enabled = available > 0) { onStart(ExamSpec.JlptType(level, type)) },
                    headlineContent = { Text(kind?.let { "${it.title}  ${it.english}" } ?: list.first().title) },
                    trailingContent = { Text("$available / ${list.sumOf { it.count }}") },
                )
            }
        }

        HorizontalDivider()
        SectionTitle("DLPT (Reading, Listening)")
        Text("ILR 0+ to 3. Passages in Japanese, questions in English.", style = MaterialTheme.typography.bodySmall)
        listOf(ExamKind.DLPT_READING, ExamKind.DLPT_LISTENING).forEach { kind ->
            val available = coverage.filter { it.exam == kind }.sumOf { it.total }
            Text("${kind.title} · $available items", style = MaterialTheme.typography.titleSmall)
            if (available == 0) {
                Text("No items installed. Import a DLPT item bank from Me → Import & export.", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(180 to "Full (180)", 60 to "60 min", 30 to "30 min").forEach { (minutes, label) ->
                        OutlinedButton(onClick = { onStart(ExamSpec.Dlpt(kind, minutes)) }) { Text(label) }
                    }
                }
            }
        }

        HorizontalDivider()
        SectionTitle("OPI (speaking)")
        OutlinedButton(onClick = onOpi) { Text("Practice interview") }

        HorizontalDivider()
        SectionTitle("History")
        if (history.isEmpty()) Text("No attempts yet.", style = MaterialTheme.typography.bodyMedium)
        val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        history.forEach { a ->
            ListItem(
                modifier = Modifier.clickable { onOpenAttempt(a.id) },
                headlineContent = { Text(a.summary) },
                supportingContent = { Text("${a.mode.title} · ${format.format(Date(a.submittedAt.toEpochMilliseconds()))}") },
                trailingContent = { Text("›", Modifier.width(16.dp)) },
            )
        }
    }
}
