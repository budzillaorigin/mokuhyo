package app.tsumugi.android.features.writing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.coverage.DifficultyScore
import app.tsumugi.reader.SummaryGradeResult
import app.tsumugi.writing.PhraseFlag
import app.tsumugi.writing.RegisterReport
import app.tsumugi.writing.RegisterRewrite
import app.tsumugi.writing.SentenceCorrection
import app.tsumugi.writing.SpeechRegister
import app.tsumugi.writing.StudioDraft
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SpeechRegister?.label(): String = stringResource(
    when (this) {
        SpeechRegister.CASUAL -> R.string.ws_register_casual
        SpeechRegister.POLITE -> R.string.ws_register_polite
        SpeechRegister.FORMAL -> R.string.ws_register_formal
        null -> R.string.ws_register_any
    },
)

/** The writing studio's drafts (BRIEF_V2 §6.13, D-297): newest first, synced; reader-task drafts are tagged. */
@Composable
fun WritingStudioScreen(onOpenDraft: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var drafts by remember { mutableStateOf<List<StudioDraft>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<StudioDraft?>(null) }
    LaunchedEffect(attempt) {
        error = null
        runCatching { graph.writingStudio.drafts() }.onSuccess { drafts = it }.onFailure { error = it.readable() }
    }
    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            text = { Text(stringResource(R.string.ws_delete_confirm, d.title.ifBlank { stringResource(R.string.ws_untitled) })) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { runCatching { graph.writingStudio.delete(d.id) }.onFailure { error = it.readable() }; attempt++ }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ws_intro), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = {
            scope.launch { runCatching { graph.writingStudio.createDraft() }.onSuccess { onOpenDraft(it.id) }.onFailure { error = it.readable() } }
        }) { Text(stringResource(R.string.ws_new_draft)) }
        val list = drafts
        when {
            error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ })
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list.isEmpty() -> Notice(stringResource(R.string.ws_no_drafts))
            else -> list.forEach { d ->
                ListItem(
                    modifier = Modifier.clickable { onOpenDraft(d.id) },
                    headlineContent = { JaText(d.title.ifBlank { d.body.lineSequence().firstOrNull { it.isNotBlank() }?.take(40) ?: stringResource(R.string.ws_untitled) }, maxLines = 1) },
                    supportingContent = {
                        Text(
                            buildList {
                                add(format.format(Date(d.updatedAt)))
                                add(stringResource(R.string.ws_chars, d.body.trim().length))
                                if (d.readerStoryId != null) add(stringResource(R.string.ws_reader_task))
                            }.joinToString(" · "),
                        )
                    },
                    trailingContent = { TextButton(onClick = { confirmDelete = d }) { Text(stringResource(R.string.action_delete)) } },
                )
            }
        }
    }
}

/**
 * One draft: title, target register and text (saved as you type), then the on-demand checks: register (rules,
 * offline), better expressions (thesaurus), readability (difficulty score), corrections and rewrites (the learner's
 * model, labeled AI), and for a graded reader's output task, the summary grade.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WritingDraftScreen(id: String, onOpenCluster: (String) -> Unit, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    val studio = graph.writingStudio
    var draft by remember(id) { mutableStateOf<StudioDraft?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var title by remember(id) { mutableStateOf("") }
    var body by remember(id) { mutableStateOf("") }
    var register by remember(id) { mutableStateOf<SpeechRegister?>(null) }
    var saveError by remember(id) { mutableStateOf<String?>(null) }
    var saved by remember(id) { mutableStateOf(true) }
    LaunchedEffect(id, attempt) {
        error = null
        runCatching { studio.draft(id) }.onSuccess { d ->
            draft = d
            if (d != null) { title = d.title; body = d.body; register = d.targetRegister }
        }.onFailure { error = it.readable() }
        loaded = true
    }
    // Autosave: a short pause after the last change (last writer wins across devices, D-275).
    LaunchedEffect(title, body, register) {
        val d = draft ?: return@LaunchedEffect
        if (title == d.title && body == d.body && register == d.targetRegister) { saved = true; return@LaunchedEffect }
        saved = false
        delay(800)
        runCatching { studio.update(id, title, body, register) }
            .onSuccess { if (it != null) draft = it; saved = true; saveError = null }
            .onFailure { saveError = it.readable() }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val d = draft
        when {
            error != null -> { ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ }); return@Column }
            !loaded -> { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            d == null -> { Notice(stringResource(R.string.ws_draft_missing)); return@Column }
        }
        d!!
        d.taskPrompt?.takeIf { it.isNotBlank() }?.let { prompt ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.ws_task), style = MaterialTheme.typography.labelMedium)
                    JaText(prompt, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.ws_title)) }, textStyle = MaterialTheme.typography.bodyLarge.japanese())
        Text(stringResource(R.string.ws_target_register), style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf<SpeechRegister?>(null) + SpeechRegister.entries).forEach { r -> FilterChip(register == r, { register = r }, { Text(r.label()) }) }
        }
        OutlinedTextField(
            body, { body = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp),
            label = { Text(stringResource(R.string.ws_body)) }, textStyle = MaterialTheme.typography.bodyLarge.japanese(),
            supportingText = { Text(stringResource(R.string.ws_chars, body.trim().length) + " · " + stringResource(if (saved) R.string.ws_saved else R.string.ws_saving)) },
        )
        saveError?.let { Notice(stringResource(R.string.ws_save_failed, it)) }
        HorizontalDivider()
        SectionTitle(stringResource(R.string.ws_checks))
        Text(stringResource(R.string.ws_checks_hint), style = MaterialTheme.typography.bodySmall)
        RegisterSection(body, register, onOpenAiSettings)
        SuggestionsSection(body, onOpenCluster)
        ReadabilitySection(body)
        CorrectionsSection(body, onOpenAiSettings)
        if (d.readerStoryId != null) ReaderTaskGrade(d.copy(title = title, body = body), onOpenAiSettings)
    }
}

/** Runs [work] on demand, showing a progress bar and an error with Retry (F-33). */
@Composable
private fun <T> OnDemand(
    label: String,
    key: Any?,
    enabled: Boolean,
    ai: Boolean = false,
    work: suspend () -> T,
    content: @Composable (T) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<Pair<Any?, T>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun run() {
        busy = true
        error = null
        scope.launch {
            runCatching { work() }.onSuccess { result = key to it }.onFailure { error = it.readable() }
            busy = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = ::run, enabled = enabled && !busy) { Text(label) }
        if (ai) AiBadge()
        if (result != null && result!!.first != key) Text(stringResource(R.string.ws_stale), style = MaterialTheme.typography.labelSmall)
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = ::run) }
    result?.let { (_, value) -> Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) { content(value) } }
}

@Composable
private fun RegisterSection(body: String, target: SpeechRegister?, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    OnDemand(stringResource(R.string.ws_check_register), body to target, body.isNotBlank(), work = { graph.writingStudio.registerCheck(body, target) }) { report: RegisterReport ->
        Text(
            stringResource(R.string.ws_register_summary, report.dominant.label(), report.expected.label()),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (report.counts.isNotEmpty()) {
            val names = SpeechRegister.entries.associateWith { registerName(it) }
            Text(report.counts.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { "${names.getValue(it.key)} ${it.value}" }, style = MaterialTheme.typography.bodySmall)
        }
        when {
            report.expected == null -> Notice(stringResource(R.string.ws_register_none))
            report.outliers.isEmpty() -> Notice(stringResource(R.string.ws_register_consistent))
            else -> {
                Text(stringResource(R.string.ws_register_outliers, report.outliers.size), style = MaterialTheme.typography.titleSmall)
                report.outliers.forEach { s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            JaText(s.text.trim(), style = MaterialTheme.typography.bodyLarge)
                            Tag(s.register.label())
                            if (s.markers.isNotEmpty()) Text(s.markers.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            RewriteButton(s.text.trim(), report.expected!!, onOpenAiSettings)
                        }
                    }
                }
            }
        }
    }
}

private val registerNames = mapOf(SpeechRegister.CASUAL to R.string.ws_register_casual, SpeechRegister.POLITE to R.string.ws_register_polite, SpeechRegister.FORMAL to R.string.ws_register_formal)

@Composable
private fun registerName(r: SpeechRegister): String = stringResource(registerNames.getValue(r))

@Composable
private fun RewriteButton(sentence: String, register: SpeechRegister, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    OnDemand(stringResource(R.string.ws_rewrite_as, register.label()), sentence, true, ai = true, work = { graph.writingStudio.rewrite(sentence, register) }) { r: RegisterRewrite ->
        if (r.rewrite != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                JaText(r.rewrite!!, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                AiBadge(r.engine)
            }
            if (r.notes.isNotBlank()) Text(r.notes, style = MaterialTheme.typography.bodySmall)
        } else {
            Notice(context.getString(R.string.ws_ai_unavailable, r.unavailable.orEmpty()), actionLabel = stringResource(R.string.ai_open_settings), onAction = onOpenAiSettings)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionsSection(body: String, onOpenCluster: (String) -> Unit) {
    val graph = rememberGraph()
    OnDemand(stringResource(R.string.ws_suggest), body, body.isNotBlank(), work = {
        val available = graph.thesaurus()?.available() == true
        available to (if (available) graph.writingStudio.suggestions(body) else emptyList())
    }) { (available: Boolean, flags: List<PhraseFlag>) ->
        when {
            !available -> Notice(stringResource(R.string.ws_suggest_no_thesaurus))
            flags.isEmpty() -> Notice(stringResource(R.string.ws_suggest_none))
            else -> flags.distinctBy { it.lemma }.forEach { f ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JaText(stringResource(R.string.ws_suggest_word, f.surface, f.lemma), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.clusters.forEach { c -> AssistChip(onClick = { onOpenCluster(c.id) }, label = { JaText(c.ja) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadabilitySection(body: String) {
    val graph = rememberGraph()
    OnDemand(stringResource(R.string.ws_readability), body, body.isNotBlank(), work = { Box(graph.writingStudio.readability(body)) }) { box: Box<DifficultyScore?> ->
        val s = box.value
        if (s == null) {
            Notice(stringResource(R.string.ws_readability_none))
        } else {
            Text(stringResource(R.string.ws_readability_value, s.label, s.textScore), style = MaterialTheme.typography.titleSmall)
            s.knownWordRatio?.let { Text(stringResource(R.string.ws_readability_known, (it * 100).toInt()), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Nullable results through [OnDemand], whose "no result yet" is null. */
private class Box<T>(val value: T)

@Composable
private fun CorrectionsSection(body: String, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    OnDemand(stringResource(R.string.ws_correct), body, body.isNotBlank(), ai = true, work = { graph.writingStudio.corrections(body) }) { list: List<SentenceCorrection> ->
        val unavailable = list.firstOrNull { it.result == null }?.unavailable
        if (list.isNotEmpty() && list.all { it.result == null }) {
            Notice(context.getString(R.string.ws_ai_unavailable, unavailable.orEmpty()), actionLabel = stringResource(R.string.ai_open_settings), onAction = onOpenAiSettings)
            return@OnDemand
        }
        list.forEach { c -> CorrectionCard(c) }
    }
}

@Composable
private fun CorrectionCard(c: SentenceCorrection) {
    val out = c.result
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JaText(c.sentence.trim(), style = MaterialTheme.typography.bodyMedium)
            when {
                out == null -> Text(stringResource(R.string.ws_correction_failed, c.unavailable.orEmpty()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                out.isCorrect -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.ws_correct_ok), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    AiBadge(c.engine)
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("→", style = MaterialTheme.typography.bodyLarge)
                        JaText(out.corrected, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        AiBadge(c.engine)
                    }
                    out.edits.forEach { e -> JaText("「${e.original}」→「${e.replacement}」 ${e.reason}", style = MaterialTheme.typography.bodySmall) }
                    if (out.explanation.isNotBlank()) Text(out.explanation, style = MaterialTheme.typography.bodySmall)
                    if (out.isUnsure) Text(stringResource(R.string.ws_correct_unsure), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ReaderTaskGrade(draft: StudioDraft, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    HorizontalDivider()
    SectionTitle(stringResource(R.string.ws_task_grade_title))
    OnDemand(stringResource(R.string.gr_task_grade), draft.body, draft.body.isNotBlank(), ai = true, work = { Box(graph.writingStudio.gradeReaderTask(draft)) }) { box: Box<SummaryGradeResult?> ->
        when (val r = box.value) {
            is SummaryGradeResult.Graded -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.gr_grade_total, r.grade.total), style = MaterialTheme.typography.titleMedium)
                        AiBadge(r.engine)
                    }
                    Text(stringResource(R.string.gr_grade_parts, r.grade.content, r.grade.accuracy, r.grade.language), style = MaterialTheme.typography.bodyMedium)
                    Text(r.grade.feedback, style = MaterialTheme.typography.bodyMedium)
                    if (r.grade.corrected.isNotBlank()) {
                        Text(stringResource(R.string.gr_grade_corrected), style = MaterialTheme.typography.titleSmall)
                        JaText(r.grade.corrected, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            is SummaryGradeResult.Unavailable -> Notice(
                context.getString(R.string.gr_grade_unavailable, r.reason),
                actionLabel = stringResource(R.string.ai_open_settings), onAction = onOpenAiSettings,
            )
            null -> Notice(stringResource(R.string.ws_task_story_missing))
        }
    }
}
