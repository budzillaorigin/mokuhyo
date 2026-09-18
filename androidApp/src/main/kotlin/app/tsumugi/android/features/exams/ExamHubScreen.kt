package app.tsumugi.android.features.exams

import app.tsumugi.android.ui.JaText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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

/** Localized title of an exam form (top bar and preview). */
@Composable
fun ExamSpec.displayTitle(): String = when (this) {
    is ExamSpec.JlptMock -> stringResource(R.string.exam_title_mock, level)
    is ExamSpec.JlptSection -> "N$level · $sectionTitle"
    is ExamSpec.JlptType -> "N$level · " + (JlptItemType.of(type)?.english ?: type)
    is ExamSpec.Dlpt -> "${exam.title} · " + if (minutes >= 180) stringResource(R.string.exam_full_length) else stringResource(R.string.minutes_short, minutes)
}

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
        Notice(stringResource(R.string.exam_disclaimer))
        if (!loaded) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        SectionTitle("JLPT")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (5 downTo 1).forEach { l -> FilterChip(level == l, { level = l }, { Text("N$l") }, Modifier.semantics { role = Role.RadioButton }) }
        }
        val bp = blueprints?.level(level)
        val cov = coverage.firstOrNull { it.exam == ExamKind.JLPT && it.level == "N$level" }
        if (bp == null) {
            Notice(stringResource(R.string.exam_pack_missing), actionLabel = stringResource(R.string.exam_import_bank), onAction = onImport)
        } else {
            Text(stringResource(R.string.exam_available, cov?.total ?: 0, bp.itemCount, bp.totalMinutes), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { onStart(ExamSpec.JlptMock(level)) }, enabled = (cov?.total ?: 0) > 0) { Text(stringResource(R.string.exam_full_mock, bp.totalMinutes)) }
            Text(stringResource(R.string.exam_section_drill), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bp.sections.forEach { s ->
                    val available = s.items.sumOf { cov?.types?.get(it.type) ?: 0 }
                    OutlinedButton(onClick = { onStart(ExamSpec.JlptSection(level, s.id, s.title)) }, enabled = available > 0) {
                        JaText("${s.title} · " + stringResource(R.string.minutes_short, s.minutes), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Text(stringResource(R.string.exam_type_drill), style = MaterialTheme.typography.titleSmall)
            val specs = bp.sections.flatMap { it.items }.groupBy { it.type }
            specs.forEach { (type, list) ->
                val available = cov?.types?.get(type) ?: 0
                val kind = JlptItemType.of(type)
                ListItem(
                    modifier = Modifier.clickable(enabled = available > 0) { onStart(ExamSpec.JlptType(level, type)) },
                    headlineContent = { JaText(kind?.let { "${it.title}  ${it.english}" } ?: list.first().title) },
                    trailingContent = { Text("$available / ${list.sumOf { it.count }}") },
                )
            }
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.exam_dlpt_title))
        Text(stringResource(R.string.exam_dlpt_hint), style = MaterialTheme.typography.bodySmall)
        listOf(ExamKind.DLPT_READING, ExamKind.DLPT_LISTENING).forEach { kind ->
            val available = coverage.filter { it.exam == kind }.sumOf { it.total }
            Text(stringResource(R.string.exam_kind_items, kind.title, available), style = MaterialTheme.typography.titleSmall)
            if (available == 0) {
                Text(stringResource(R.string.exam_dlpt_none), style = MaterialTheme.typography.bodySmall)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(180, 60, 30).forEach { minutes ->
                        val label = if (minutes >= 180) stringResource(R.string.exam_dlpt_full) else stringResource(R.string.minutes_short, minutes)
                        OutlinedButton(onClick = { onStart(ExamSpec.Dlpt(kind, minutes)) }) { Text(label) }
                    }
                }
            }
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.exam_opi_title))
        OutlinedButton(onClick = onOpi) { Text(stringResource(R.string.exam_opi_start)) }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.exam_history))
        if (history.isEmpty()) Text(stringResource(R.string.exam_no_attempts), style = MaterialTheme.typography.bodyMedium)
        val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        history.forEach { a ->
            ListItem(
                modifier = Modifier.clickable { onOpenAttempt(a.id) },
                headlineContent = { Text(a.summary) },
                supportingContent = { Text("${a.mode.title} · ${format.format(Date(a.submittedAt.toEpochMilliseconds()))}") },
                trailingContent = { Text("›", Modifier.width(16.dp).clearAndSetSemantics {}) },
            )
        }
    }
}
