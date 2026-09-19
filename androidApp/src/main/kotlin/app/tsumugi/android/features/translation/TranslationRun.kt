package app.tsumugi.android.features.translation

import android.app.Application
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.ai.prompts.GradeTranslation
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.keyedViewModel
import app.tsumugi.android.platform.rememberMicPermission
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.translation.DiffKind
import app.tsumugi.translation.TranslationAttempt
import app.tsumugi.translation.TranslationDiff
import app.tsumugi.translation.TranslationDirection
import app.tsumugi.translation.TranslationGradeResult
import app.tsumugi.translation.TranslationMode
import app.tsumugi.translation.TranslationPassage
import app.tsumugi.translation.TranslationRubric
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a run is: loading, translating (sight: ready → listening → transcribing → editing), grading, done. */
internal enum class RunPhase { LOADING, MISSING, FAILED, READY, LISTENING, TRANSCRIBING, EDITING, GRADING, RESULT }

/** A written or sight translation run. The service grades and saves; this holds the draft, the clock and the capture. */
internal class TranslationRunViewModel(app: Application, private val passageId: String, val mode: TranslationMode) : ViewModel() {
    private val graph = (app as TsumugiApplication).graph
    private val service = graph.translationWorkbench
    private val context = app

    var phase by mutableStateOf(RunPhase.LOADING)
        private set
    var passage by mutableStateOf<TranslationPassage?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var text by mutableStateOf("")
    var limitMs by mutableStateOf(0L)
        private set
    var elapsedMs by mutableStateOf(0L)
        private set
    /** Why the capture produced no text (a string resource id and its argument). */
    var captureNote by mutableStateOf<Pair<Int, String?>?>(null)
        private set
    var result by mutableStateOf<TranslationGradeResult?>(null)
        private set
    var selfSaved by mutableStateOf<TranslationAttempt?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    val selfScores = mutableStateListOf(-1, -1, -1, -1)

    private var startedAt = 0L
    private var ticker: Job? = null
    private var speech: SightSpeech? = null

    init { load() }

    fun load() {
        phase = RunPhase.LOADING
        viewModelScope.launch {
            runCatching { resolvePassage(graph, passageId) }
                .onSuccess { p ->
                    passage = p
                    if (p == null) {
                        phase = RunPhase.MISSING
                    } else {
                        limitMs = service.timeLimitMs(p)
                        phase = if (mode == TranslationMode.SIGHT) RunPhase.READY else RunPhase.EDITING
                        startedAt = System.currentTimeMillis()
                        if (mode == TranslationMode.WRITTEN) startTicker()
                    }
                }
                .onFailure { error = it.readable(); phase = RunPhase.FAILED }
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (true) {
                elapsedMs = System.currentTimeMillis() - startedAt
                // Sight mode: time's up stops the capture (the attempt still counts, marked over time if later).
                if (mode == TranslationMode.SIGHT && phase == RunPhase.LISTENING && elapsedMs >= limitMs) stopSpeaking()
                delay(250)
            }
        }
    }

    /** Sight mode: the timer starts and the recognizer listens in the target language. */
    fun startSpeaking() {
        val p = passage ?: return
        captureNote = null
        val tag = if (p.direction == TranslationDirection.JE) "en-US" else "ja-JP"
        val s = SightSpeech(context, graph, tag)
        speech = s
        startedAt = System.currentTimeMillis()
        phase = RunPhase.LISTENING
        startTicker()
        viewModelScope.launch {
            when (val outcome = s.start(((limitMs / 1000) + 60).toInt())) {
                null -> Unit
                else -> { ticker?.cancel(); phase = RunPhase.EDITING; note(outcome) }
            }
        }
    }

    fun stopSpeaking() {
        val s = speech ?: return
        if (phase != RunPhase.LISTENING) return
        ticker?.cancel()
        elapsedMs = System.currentTimeMillis() - startedAt
        phase = RunPhase.TRANSCRIBING
        viewModelScope.launch {
            val outcome = s.stop()
            speech = null
            if (outcome is SightSpeech.Outcome.Text) text = listOf(text, outcome.text).filter { it.isNotBlank() }.joinToString(" ") else note(outcome)
            phase = RunPhase.EDITING
        }
    }

    private fun note(o: SightSpeech.Outcome) {
        captureNote = when (o) {
            SightSpeech.Outcome.NoRecognizer -> R.string.tw_no_recognizer to null
            SightSpeech.Outcome.Nothing -> R.string.tw_heard_nothing to null
            is SightSpeech.Outcome.Failed -> R.string.tw_recognizer_failed to o.message
            is SightSpeech.Outcome.Text -> null
        }
    }

    fun grade() {
        val p = passage ?: return
        if (text.isBlank()) return
        ticker?.cancel()
        val duration = if (mode == TranslationMode.WRITTEN) System.currentTimeMillis() - startedAt else elapsedMs
        elapsedMs = duration
        phase = RunPhase.GRADING
        viewModelScope.launch {
            runCatching { service.grade(p, text, mode, duration) }
                .onSuccess { result = it; phase = RunPhase.RESULT }
                .onFailure { saveError = it.readable(); phase = RunPhase.EDITING }
        }
    }

    fun saveSelfAssessment() {
        val p = passage ?: return
        if (selfScores.any { it < 0 }) return
        viewModelScope.launch {
            runCatching { service.selfAssess(p, text, mode, elapsedMs, selfScores[0], selfScores[1], selfScores[2], selfScores[3]) }
                .onSuccess { selfSaved = it; saveError = null }
                .onFailure { saveError = it.readable() }
        }
    }

    override fun onCleared() {
        speech?.cancel()
    }
}

/** Written or sight translation of one passage, then the result (§6.12). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationRunScreen(passageId: String, mode: TranslationMode, key: String, onOpenAiSettings: () -> Unit, onDone: () -> Unit) {
    val vm = keyedViewModel(key) { TranslationRunViewModel(it, passageId, mode) }
    val mic = rememberMicPermission()
    DisposableEffect(vm) { onDispose { vm.stopSpeaking() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (vm.phase) {
            RunPhase.LOADING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            RunPhase.FAILED -> ErrorState(stringResource(R.string.tw_load_failed, vm.error.orEmpty()), onRetry = vm::load)
            RunPhase.MISSING -> Notice(stringResource(if (passageId.startsWith("user:")) R.string.tw_imported_gone else R.string.tw_passage_missing))
            else -> {
                val p = vm.passage ?: return@Column
                PassageHeader(p)
                PassageText(p)
                Text(
                    stringResource(if (p.direction == TranslationDirection.JE) R.string.tw_translate_into_en else R.string.tw_translate_into_ja),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (vm.phase == RunPhase.RESULT) {
                    ResultView(vm, p, onOpenAiSettings, onDone)
                } else {
                    if (mode == TranslationMode.SIGHT) SightControls(vm, mic.granted, mic.request)
                    if (vm.phase == RunPhase.EDITING || vm.phase == RunPhase.GRADING || mode == TranslationMode.WRITTEN) {
                        OutlinedTextField(
                            vm.text, { vm.text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
                            enabled = vm.phase != RunPhase.GRADING,
                            label = { Text(stringResource(if (mode == TranslationMode.SIGHT) R.string.tw_transcript else R.string.tw_your_translation)) },
                            textStyle = if (p.direction == TranslationDirection.EJ) MaterialTheme.typography.bodyLarge.japanese() else MaterialTheme.typography.bodyLarge,
                        )
                        vm.saveError?.let { Text(stringResource(R.string.tw_grade_failed, it), color = MaterialTheme.colorScheme.error) }
                        if (vm.phase == RunPhase.GRADING) {
                            Text(stringResource(R.string.tw_grading))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            Button(onClick = vm::grade, enabled = vm.text.isNotBlank()) { Text(stringResource(R.string.tw_grade)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SightControls(vm: TranslationRunViewModel, micGranted: Boolean, requestMic: () -> Unit) {
    val remaining = vm.limitMs - vm.elapsedMs
    when (vm.phase) {
        RunPhase.READY -> {
            Text(stringResource(R.string.tw_sight_ready), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.tw_sight_limit, clock(vm.limitMs)), style = MaterialTheme.typography.labelLarge)
            if (!micGranted) {
                Notice(stringResource(R.string.tw_mic_needed), actionLabel = stringResource(R.string.tw_allow_mic), onAction = requestMic)
            } else {
                Button(onClick = vm::startSpeaking) { Text(stringResource(R.string.tw_sight_start)) }
            }
        }
        RunPhase.LISTENING -> {
            Text(
                if (remaining > 0) stringResource(R.string.tw_time_left, clock(remaining)) else stringResource(R.string.tw_time_up),
                style = MaterialTheme.typography.headlineMedium,
            )
            LinearProgressIndicator(progress = { (vm.elapsedMs.toFloat() / vm.limitMs.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.tw_listening), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Button(onClick = vm::stopSpeaking) { Text(stringResource(R.string.tw_sight_stop)) }
        }
        RunPhase.TRANSCRIBING -> {
            Text(stringResource(R.string.tw_transcribing), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        else -> {
            Text(stringResource(R.string.tw_took, clock(vm.elapsedMs), clock(vm.limitMs)), style = MaterialTheme.typography.labelLarge)
            vm.captureNote?.let { (res, arg) ->
                Text(if (arg != null) stringResource(res, arg) else stringResource(res), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
            }
            if (vm.phase == RunPhase.EDITING && micGranted) OutlinedButton(onClick = vm::startSpeaking) { Text(stringResource(R.string.tw_try_again)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultView(vm: TranslationRunViewModel, p: TranslationPassage, onOpenAiSettings: () -> Unit, onDone: () -> Unit) {
    val targetJapanese = p.direction == TranslationDirection.EJ
    SectionTitle(stringResource(R.string.tw_result))
    if (vm.mode == TranslationMode.SIGHT) Text(stringResource(R.string.tw_took, clock(vm.elapsedMs), clock(vm.limitMs)), style = MaterialTheme.typography.labelLarge)
    Text(stringResource(R.string.tw_your_translation), style = MaterialTheme.typography.titleSmall)
    TargetText(vm.text, targetJapanese)
    when (val r = vm.result) {
        is TranslationGradeResult.Graded -> {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.tw_score, r.attempt.score), style = MaterialTheme.typography.headlineMedium)
                        AiBadge(r.engine)
                    }
                    Text(stringResource(R.string.tw_not_official), style = MaterialTheme.typography.bodySmall)
                    CriteriaScores(r.grade.accuracy, r.grade.completeness, r.grade.register, r.grade.naturalness)
                }
            }
            if (r.grade.feedback.isNotBlank()) {
                Text(stringResource(R.string.tw_feedback), style = MaterialTheme.typography.titleSmall)
                Text(r.grade.feedback)
            }
            if (r.grade.issues.isNotEmpty()) {
                Text(stringResource(R.string.tw_issues), style = MaterialTheme.typography.titleSmall)
                r.grade.issues.forEach { IssueRow(it, targetJapanese) }
            }
            if (r.grade.better.isNotBlank()) {
                Text(stringResource(R.string.tw_better), style = MaterialTheme.typography.titleSmall)
                TargetText(r.grade.better, targetJapanese)
            }
            ReferenceAndDiff(p, r.diff, targetJapanese)
        }
        is TranslationGradeResult.Unavailable -> {
            Notice(stringResource(R.string.tw_ungraded, r.reason), actionLabel = stringResource(R.string.tw_open_ai), onAction = onOpenAiSettings)
            ReferenceAndDiff(p, r.diff, targetJapanese)
            SelfAssessment(vm)
        }
        null -> Unit
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
    }
}

@Composable
private fun TargetText(text: String, japanese: Boolean) {
    if (japanese) JaText(text, style = MaterialTheme.typography.bodyLarge) else Text(text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun ReferenceAndDiff(p: TranslationPassage, diff: TranslationDiff?, targetJapanese: Boolean) {
    if (p.hasReference) {
        Text(stringResource(R.string.tw_reference), style = MaterialTheme.typography.titleSmall)
        if (p.isAiGenerated) AiBadge()
        TargetText(p.reference, targetJapanese)
    }
    if (p.keyPoints.isNotEmpty()) {
        Text(stringResource(R.string.tw_key_points), style = MaterialTheme.typography.titleSmall)
        p.keyPoints.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
    diff?.let { DiffView(it, targetJapanese) }
}

/** The offline diff: struck through = only in the learner's version, underlined = only in the reference. */
@Composable
internal fun DiffView(diff: TranslationDiff, japanese: Boolean) {
    val extra = MaterialTheme.colorScheme.error
    val missing = MaterialTheme.colorScheme.primary
    val sep = if (japanese) "" else " "
    val text = buildAnnotatedString {
        diff.segments.forEachIndexed { i, s ->
            if (i > 0) append(sep)
            when (s.kind) {
                DiffKind.SAME -> append(s.text)
                DiffKind.EXTRA -> withStyle(SpanStyle(color = extra, textDecoration = TextDecoration.LineThrough)) { append(s.text) }
                DiffKind.MISSING -> withStyle(SpanStyle(color = missing, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) { append(s.text) }
            }
        }
    }
    Text(stringResource(R.string.tw_diff), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (japanese) ja(text) else text, style = if (japanese) MaterialTheme.typography.bodyLarge.japanese() else MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.tw_diff_legend), style = MaterialTheme.typography.labelSmall)
            Text(stringResource(R.string.tw_diff_overlap, (diff.overlap * 100).toInt()), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun criterionName(key: String): String = stringResource(
    when (key) {
        "accuracy" -> R.string.tw_crit_accuracy
        "completeness" -> R.string.tw_crit_completeness
        "register" -> R.string.tw_crit_register
        else -> R.string.tw_crit_naturalness
    },
)

@Composable
private fun criterionQuestion(key: String): String = stringResource(
    when (key) {
        "accuracy" -> R.string.tw_q_accuracy
        "completeness" -> R.string.tw_q_completeness
        "register" -> R.string.tw_q_register
        else -> R.string.tw_q_naturalness
    },
)

private val LEVELS = mapOf(
    "accuracy" to listOf(R.string.tw_l_accuracy_0, R.string.tw_l_accuracy_1, R.string.tw_l_accuracy_2, R.string.tw_l_accuracy_3, R.string.tw_l_accuracy_4),
    "completeness" to listOf(R.string.tw_l_completeness_0, R.string.tw_l_completeness_1, R.string.tw_l_completeness_2, R.string.tw_l_completeness_3, R.string.tw_l_completeness_4),
    "register" to listOf(R.string.tw_l_register_0, R.string.tw_l_register_1, R.string.tw_l_register_2, R.string.tw_l_register_3, R.string.tw_l_register_4),
    "naturalness" to listOf(R.string.tw_l_naturalness_0, R.string.tw_l_naturalness_1, R.string.tw_l_naturalness_2, R.string.tw_l_naturalness_3, R.string.tw_l_naturalness_4),
)

/** The four rubric scores (0–4) as labelled bars. */
@Composable
internal fun CriteriaScores(accuracy: Int, completeness: Int, register: Int, naturalness: Int) {
    val scores = listOf(accuracy, completeness, register, naturalness)
    TranslationRubric.criteria.forEachIndexed { i, c ->
        val s = scores.getOrElse(i) { 0 }.coerceIn(0, 4)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.tw_crit_score, criterionName(c.key), s), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { s / 4f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun issueKind(kind: String): String = when (kind) {
    "mistranslation" -> stringResource(R.string.tw_issue_mistranslation)
    "omission" -> stringResource(R.string.tw_issue_omission)
    "addition" -> stringResource(R.string.tw_issue_addition)
    "register" -> stringResource(R.string.tw_issue_register)
    "unnatural" -> stringResource(R.string.tw_issue_unnatural)
    "grammar" -> stringResource(R.string.tw_issue_grammar)
    "term" -> stringResource(R.string.tw_issue_term)
    else -> kind
}

@Composable
private fun IssueRow(issue: GradeTranslation.Issue, targetJapanese: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tag(issueKind(issue.kind))
            if (issue.attemptSpan.isNotBlank()) {
                val span = buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.error)) { append(issue.attemptSpan) } }
                Text(if (targetJapanese) ja(span) else span)
            }
            if (issue.referenceSpan.isNotBlank()) TargetText("→ " + issue.referenceSpan, targetJapanese)
            Text(issue.note, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** No model: the learner scores themselves on the same rubric (D-271); saved as a SELF attempt. */
@Composable
private fun SelfAssessment(vm: TranslationRunViewModel) {
    SectionTitle(stringResource(R.string.tw_self_title))
    val saved = vm.selfSaved
    if (saved != null) {
        Text(stringResource(R.string.tw_self_saved, saved.score), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleMedium)
        return
    }
    Text(stringResource(R.string.tw_self_hint), style = MaterialTheme.typography.bodySmall)
    TranslationRubric.criteria.forEachIndexed { i, c ->
        Text(criterionName(c.key), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        Text(criterionQuestion(c.key), style = MaterialTheme.typography.bodySmall)
        Column(Modifier.selectableGroup()) {
            LEVELS.getValue(c.key).forEachIndexed { level, res ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp)
                        .selectable(selected = vm.selfScores[i] == level, role = Role.RadioButton, onClick = { vm.selfScores[i] = level }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = vm.selfScores[i] == level, onClick = null)
                    Text("$level · " + stringResource(res), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    vm.saveError?.let { Text(stringResource(R.string.tw_save_failed, it), color = MaterialTheme.colorScheme.error) }
    Button(onClick = vm::saveSelfAssessment, enabled = vm.selfScores.none { it < 0 }) { Text(stringResource(R.string.tw_self_save)) }
}
