package app.tsumugi.android.features.exams

import app.tsumugi.exam.dlpt.DlptRange
import androidx.compose.runtime.mutableStateMapOf

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
import app.tsumugi.exam.InProgressAttempt
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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

    /** [range]/[textTypes]: the upper-range form and the text-type filter (BRIEF_V2 §6.16, G-08; empty = all types). */
    data class Dlpt(
        val exam: ExamKind,
        val minutes: Int,
        val range: DlptRange = DlptRange.LOWER,
        val textTypes: Set<String> = emptySet(),
    ) : ExamSpec {
        override val title get() = "${exam.title}${if (range == DlptRange.UPPER) " (upper)" else ""} · ${if (minutes >= 180) "full length" else "$minutes min"}"
        override val strict get() = true
    }

    /** The unfinished attempt saved on this device (F-24): the runner rebuilds it with `ExamService.resume()`. */
    data class Resume(val attempt: InProgressAttempt) : ExamSpec {
        override val title get() = "${attempt.exam.title} ${attempt.level} · ${attempt.mode.title}".replace("  ", " ")
        override val strict get() = attempt.mode.strict
    }
}

/** Localized title of an exam form (top bar and preview). */
@Composable
fun ExamSpec.displayTitle(): String = when (this) {
    is ExamSpec.JlptMock -> stringResource(R.string.exam_title_mock, level)
    is ExamSpec.JlptSection -> "N$level · $sectionTitle"
    is ExamSpec.JlptType -> "N$level · " + (JlptItemType.of(type)?.english ?: type)
    is ExamSpec.Dlpt -> exam.title + (if (range == DlptRange.UPPER) " " + stringResource(R.string.dlpt_upper_short) else "") + " · " +
        (if (minutes >= 180) stringResource(R.string.exam_full_length) else stringResource(R.string.minutes_short, minutes))
    is ExamSpec.Resume -> title
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
    var inProgress by remember { mutableStateOf<InProgressAttempt?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var dlptRange by remember { mutableStateOf(DlptRange.LOWER) }
    val dlptTypes = remember { mutableStateMapOf<ExamKind, Set<String>>() }
    var dlptTextTypes by remember { mutableStateOf<Map<ExamKind, List<Pair<String, Int>>>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(dlptRange, loaded) {
        if (!loaded) return@LaunchedEffect
        dlptTextTypes = runCatching {
            val exams = graph.exams()
            listOf(ExamKind.DLPT_READING, ExamKind.DLPT_LISTENING).associateWith { exams.dlptTextTypes(it, dlptRange) }
        }.getOrDefault(emptyMap())
    }
    // Re-read on every return to the hub, so a finished or abandoned attempt updates the resume card.
    LaunchedEffect(attempt) {
        error = null
        runCatching {
            val exams = graph.exams()
            blueprints = exams.blueprints()
            coverage = exams.coverage()
            history = exams.history()
            inProgress = exams.inProgress()
        }.onSuccess { loaded = true }.onFailure { error = it.readable() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Notice(stringResource(R.string.exam_disclaimer))
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { attempt++ }) }
        if (!loaded) {
            if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        inProgress?.let { a -> ResumeCard(a, onResume = { onStart(ExamSpec.Resume(a)) }, onDiscard = { confirmDiscard = true }) }
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
        // §6.16 / G-08: lower (0+–3) or upper (3–4) range, and an optional text-type filter per test.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DlptRange.entries.forEach { r ->
                FilterChip(
                    dlptRange == r, { dlptRange = r; dlptTypes.clear() },
                    { Text(stringResource(if (r == DlptRange.UPPER) R.string.dlpt_range_upper else R.string.dlpt_range_lower)) },
                    Modifier.semantics { role = Role.RadioButton },
                )
            }
        }
        listOf(ExamKind.DLPT_READING, ExamKind.DLPT_LISTENING).forEach { kind ->
            val available = coverage.filter { it.exam == kind && it.level in dlptRange.labels }.sumOf { it.total }
            Text(stringResource(R.string.exam_kind_items, kind.title, available), style = MaterialTheme.typography.titleSmall)
            if (available == 0) {
                Text(
                    stringResource(if (dlptRange == DlptRange.UPPER) R.string.dlpt_upper_none else R.string.exam_dlpt_none),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                val types = dlptTextTypes[kind].orEmpty()
                val picked = dlptTypes[kind].orEmpty()
                if (types.isNotEmpty()) {
                    Text(stringResource(R.string.dlpt_text_types), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(picked.isEmpty(), { dlptTypes[kind] = emptySet() }, { Text(stringResource(R.string.filter_all)) })
                        types.forEach { (type, count) ->
                            FilterChip(
                                type in picked,
                                { dlptTypes[kind] = if (type in picked) picked - type else picked + type },
                                { Text("${dlptTextTypeLabel(type)} · $count") },
                            )
                        }
                    }
                    if (picked.isNotEmpty()) {
                        val items = types.filter { it.first in picked }.sumOf { it.second }
                        Text(stringResource(R.string.dlpt_filtered_items, items), style = MaterialTheme.typography.bodySmall)
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(180, 60, 30).forEach { minutes ->
                        val label = if (minutes >= 180) stringResource(R.string.exam_dlpt_full) else stringResource(R.string.minutes_short, minutes)
                        OutlinedButton(onClick = { onStart(ExamSpec.Dlpt(kind, minutes, dlptRange, picked)) }) { Text(label) }
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
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.exam_discard_title)) },
            text = { Text(stringResource(R.string.exam_discard_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    scope.launch {
                        runCatching { graph.exams().discardInProgress() }.onFailure { error = it.readable() }
                        attempt++
                    }
                }) { Text(stringResource(R.string.exam_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** F-24: the unfinished attempt, with where it stands and how much time its open section has left (wall clock). */
@Composable
private fun ResumeCard(a: InProgressAttempt, onResume: () -> Unit, onDiscard: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.exam_resume_title), style = MaterialTheme.typography.titleMedium)
            Text(ExamSpec.Resume(a).title, style = MaterialTheme.typography.bodyMedium)
            JaText(
                stringResource(R.string.exam_section_progress, a.sectionIndex + 1, a.sectionCount, a.answered, a.total) + " · " + a.sectionTitle,
                style = MaterialTheme.typography.bodySmall,
            )
            val remaining = a.sectionDeadline?.let { (it.toEpochMilliseconds() - System.currentTimeMillis()).coerceAtLeast(0) }
            when {
                a.timeUp -> Text(stringResource(R.string.exam_resume_time_up), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                remaining != null -> Text(stringResource(R.string.exam_resume_time_left, (remaining / 60_000).toInt()), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text(stringResource(if (a.timeUp) R.string.exam_resume_score else R.string.exam_resume)) }
                OutlinedButton(onClick = onDiscard) { Text(stringResource(R.string.exam_discard)) }
            }
        }
    }
}

/** A DLPT passage text type in the UI language; unknown types (newer banks) show as written. */
@Composable
fun dlptTextTypeLabel(type: String): String {
    val id = when (type) {
        "commentary" -> R.string.dlpt_tt_commentary
        "editorial" -> R.string.dlpt_tt_editorial
        "essay" -> R.string.dlpt_tt_essay
        "liaison" -> R.string.dlpt_tt_liaison
        "announcement" -> R.string.dlpt_tt_announcement
        "literary" -> R.string.dlpt_tt_literary
        "academic" -> R.string.dlpt_tt_academic
        "news" -> R.string.dlpt_tt_news
        "discussion" -> R.string.dlpt_tt_discussion
        "notice" -> R.string.dlpt_tt_notice
        "interview" -> R.string.dlpt_tt_interview
        "conversation" -> R.string.dlpt_tt_conversation
        "lecture" -> R.string.dlpt_tt_lecture
        "voicemail" -> R.string.dlpt_tt_voicemail
        "sign" -> R.string.dlpt_tt_sign
        "narrative" -> R.string.dlpt_tt_narrative
        "email" -> R.string.dlpt_tt_email
        "column" -> R.string.dlpt_tt_column
        "broadcast" -> R.string.dlpt_tt_broadcast
        "schedule" -> R.string.dlpt_tt_schedule
        "report" -> R.string.dlpt_tt_report
        "instructions" -> R.string.dlpt_tt_instructions
        "speech" -> R.string.dlpt_tt_speech
        "memo" -> R.string.dlpt_tt_memo
        "letter" -> R.string.dlpt_tt_letter
        "label" -> R.string.dlpt_tt_label
        "briefing" -> R.string.dlpt_tt_briefing
        else -> null
    }
    return id?.let { stringResource(it) } ?: type.replace('_', ' ')
}
