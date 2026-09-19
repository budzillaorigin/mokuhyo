package app.tsumugi.android.features.exams

import app.tsumugi.android.platform.VoiceLine
import app.tsumugi.audio.AudioKeys

import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.keyedViewModel
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.japanese
import app.tsumugi.exam.ExamForm
import app.tsumugi.exam.ExamResult
import app.tsumugi.exam.ExamSession
import app.tsumugi.exam.ScriptLine
import app.tsumugi.exam.jlpt.JlptItemType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable

class ExamRunViewModel(app: Application, private val spec: ExamSpec) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    private val seed = System.currentTimeMillis()

    var preview by mutableStateOf<ExamForm?>(null)
        private set
    var loaded by mutableStateOf(false)
        private set
    /** Building or resuming the form failed (F-33): shown with a retry. */
    var error by mutableStateOf<String?>(null)
        private set
    var saveError by mutableStateOf<String?>(null)
        private set
    var session by mutableStateOf<ExamSession?>(null)
        private set
    var result by mutableStateOf<ExamResult?>(null)
        private set
    var savedId by mutableStateOf<String?>(null)
        private set
    var added by mutableStateOf<Int?>(null)
        private set
    /** Bumped on every session change and clock tick; ExamSession itself isn't observable. */
    var version by mutableIntStateOf(0)
        private set

    init {
        load()
    }

    fun load() {
        loaded = false
        error = null
        viewModelScope.launch {
            try {
                if (spec is ExamSpec.Resume) {
                    // F-24: straight back into the saved attempt; sections that ran out meanwhile are already closed.
                    val resumed = graph.exams().resume()
                    session = resumed
                    if (resumed == null) error = getApplication<Application>().getString(R.string.exam_resume_gone)
                    else if (resumed.finished) submit()
                } else {
                    preview = create()?.form
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.readable()
            }
            loaded = true
            version++
        }
    }

    private suspend fun create(): ExamSession? {
        val exams = graph.exams()
        return when (spec) {
            is ExamSpec.JlptMock -> exams.jlptMock(spec.level, seed)
            is ExamSpec.JlptSection -> exams.jlptSection(spec.level, spec.sectionId, seed)
            is ExamSpec.JlptType -> exams.jlptTypeDrill(spec.level, spec.type, seed)
            is ExamSpec.Dlpt -> exams.dlpt(spec.exam, spec.minutes, seed)
            is ExamSpec.Resume -> exams.resume()
        }
    }

    /**
     * Builds the same form again (same seed) so the clock starts now, not when the preview was made, and starts
     * saving it (F-24): from here the attempt survives process death and can be resumed from the hub.
     */
    fun start() = viewModelScope.launch {
        try {
            session = create()?.also { it.begin() }
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.readable()
        }
        version++
    }

    fun changed() {
        version++
    }

    /**
     * Once a second, and whenever the screen resumes: closes every section whose deadline has passed on the wall
     * clock (there may be several after the app was in the background), then submits if that was the last one.
     */
    fun tick() {
        val s = session ?: return
        while (s.tick()) Unit
        if (s.finished && result == null) submit() else version++
    }

    fun submit() {
        val s = session ?: return
        if (result != null) return
        val r = s.submit()
        result = r
        version++
        save(r)
    }

    fun retrySave() {
        result?.let { save(it) }
    }

    private fun save(r: ExamResult) {
        saveError = null
        viewModelScope.launch {
            try {
                savedId = graph.exams().save(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveError = e.readable()
            }
        }
    }

    /** Leaving keeps a started attempt for "Resume attempt"; this throws it away instead. */
    fun discard(then: () -> Unit) {
        viewModelScope.launch {
            runCatching { graph.exams().discardInProgress() }
            then()
        }
    }

    fun addMissedToSrs() {
        val r = result ?: return
        viewModelScope.launch { added = graph.exams().addToSrs(r.missedRefs) }
    }
}

/** Runs one exam form: preview (shortfalls), timed sections, then the result. */
@Composable
fun ExamRunScreen(spec: ExamSpec, key: String, onReview: (String) -> Unit, onExit: () -> Unit) {
    val vm = keyedViewModel(key) { ExamRunViewModel(it, spec) }
    val session = vm.session
    val result = vm.result
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler(enabled = session != null && result == null) { confirmLeave = true }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.exam_leave_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (spec.strict) R.string.exam_leave_resume_strict else R.string.exam_leave_resume))
                    TextButton(onClick = { confirmLeave = false; vm.discard(onExit) }) {
                        Text(stringResource(R.string.exam_discard), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onExit() }) { Text(stringResource(R.string.exam_leave)) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.exam_stay)) } },
        )
    }
    when {
        !vm.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        vm.error != null && session == null && result == null ->
            ErrorState(stringResource(R.string.exam_start_failed, vm.error.orEmpty()), onRetry = vm::load, modifier = Modifier.padding(16.dp))
        result != null -> ResultView(vm, result, onReview, onExit)
        session != null -> Runner(vm, session, spec)
        else -> Preview(vm, spec)
    }
}

@Composable
private fun Preview(vm: ExamRunViewModel, spec: ExamSpec) {
    val form = vm.preview
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(spec.displayTitle(), style = MaterialTheme.typography.headlineSmall)
        if (form == null) {
            Notice(stringResource(R.string.exam_pack_missing))
            return@Column
        }
        if (form.isEmpty) {
            Notice(stringResource(R.string.exam_form_empty))
            return@Column
        }
        form.sections.forEach { s ->
            JaText("• " + stringResource(R.string.exam_preview_section, s.title, s.items.size) + " · " + (s.minutes?.let { stringResource(R.string.minutes_short, it) } ?: stringResource(R.string.exam_untimed)))
        }
        if (form.shortfalls.isNotEmpty()) {
            Notice(
                stringResource(R.string.exam_shortfall) + "\n" +
                    form.shortfalls.joinToString("\n") { "• ${typeLabel(it.type)}: ${it.got} / ${it.wanted}" },
            )
        }
        if (spec.strict) {
            Text(
                stringResource(R.string.exam_strict_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (form.items.any { it.item.aiGenerated }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AiBadge()
                Text(stringResource(R.string.exam_ai_items), style = MaterialTheme.typography.bodySmall)
            }
        }
        vm.error?.let { ErrorState(stringResource(R.string.exam_start_failed, it), onRetry = { vm.start() }) }
        Button(onClick = { vm.start() }) { Text(stringResource(R.string.action_start)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Runner(vm: ExamRunViewModel, session: ExamSession, spec: ExamSpec) {
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var confirmEnd by remember { mutableStateOf(false) }
    // Remaining time comes from the wall clock (section deadlines are absolute), so this loop only redraws and
    // closes sections on time; after the app was in the background, ON_RESUME catches up on every missed deadline.
    LaunchedEffect(session) {
        while (!session.finished) {
            delay(1_000)
            vm.tick()
        }
        vm.submit()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.tick() }
    @Suppress("UNUSED_VARIABLE") val v = vm.version // recompose on ticks and answers
    val section = session.section ?: return
    val formItem = session.current ?: return
    val item = formItem.item
    val passage = session.passage
    val lastSection = session.sectionIndex == session.form.sections.lastIndex

    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    JaText(section.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.exam_section_progress, session.sectionIndex + 1, session.form.sections.size, session.answeredCount, session.totalCount),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                session.remainingMs()?.let { ms ->
                    val s = ms / 1000
                    val timeLeft = stringResource(R.string.exam_time_left, (s / 60).toInt(), (s % 60).toInt())
                    Text(
                        "%d:%02d".format(s / 60, s % 60),
                        Modifier.semantics { contentDescription = timeLeft },
                        style = MaterialTheme.typography.titleLarge,
                        color = if (ms < 60_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Question navigator for the open section.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                section.items.forEachIndexed { i, f ->
                    val answered = session.choiceFor(f.item.id) != null
                    val current = i == session.index
                    val goTo = stringResource(R.string.exam_go_to, i + 1)
                    val state = stringResource(if (answered) R.string.exam_answered else R.string.exam_unanswered)
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when {
                            current -> MaterialTheme.colorScheme.primary
                            answered -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.size(48.dp)
                            .selectable(selected = current, onClick = { session.goTo(i); vm.changed() })
                            .semantics { contentDescription = goTo; stateDescription = state },
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "${i + 1}",
                                color = if (current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
            Text(
                ja(JlptItemType.of(item.type)?.let { "${it.title} · ${it.english}" } ?: formItem.typeTitle),
                style = MaterialTheme.typography.labelLarge.japanese(),
                color = MaterialTheme.colorScheme.primary,
            )
            if (passage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (passage.title.isNotBlank()) JaText(passage.title, style = MaterialTheme.typography.titleSmall)
                        if (passage.body.isNotBlank()) ExamText(passage.body, highlight = markerOf(item.stem))
                        if (passage.aiGenerated) AiBadge()
                    }
                }
            }
            val script: List<ScriptLine> = item.script.ifEmpty { passage?.script.orEmpty() }
            val audioKey = if (item.script.isNotEmpty()) item.id else passage?.id ?: item.id
            if (script.isNotEmpty()) {
                val canPlay = session.canPlayAudio(audioKey)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            session.audioPlayed(audioKey)
                            vm.changed()
                            // Rule 20: the pre-rendered line (exam/<owner>/<index into the script>) when installed, else TTS.
                            scope.launch { voices.sayAllClips(script.mapIndexed { i, l -> VoiceLine(AudioKeys.exam(audioKey, i), l.text, l.voice) }) }
                        },
                        enabled = canPlay,
                    ) { PlayLabel(stringResource(R.string.exam_play_audio)) }
                    Text(
                        stringResource(if (spec.strict) (if (canPlay) R.string.exam_plays_once else R.string.exam_already_played) else R.string.exam_replay_any),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            ExamText(item.stem, style = MaterialTheme.typography.titleMedium)
            if (item.aiGenerated) AiBadge()
            val chosen = session.choiceFor(item.id)
            // The choices form a radio group: one selectable node per choice, announced as "radio button, selected".
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item.choices.forEachIndexed { i, c ->
                Card(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = chosen == i, role = Role.RadioButton, onClick = { session.choose(i); vm.changed() }),
                    colors = CardDefaults.cardColors(
                        containerColor = if (chosen == i) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = CardDefaults.outlinedCardBorder(),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen == i, onClick = null)
                        Text("${i + 1}  ", style = MaterialTheme.typography.bodyLarge)
                        ExamText(c, Modifier.weight(1f))
                    }
                }
            }
            }
        }
        HorizontalDivider()
        val previousLabel = stringResource(R.string.exam_previous_question)
        val nextLabel = stringResource(R.string.exam_next_question)
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { session.previous(); vm.changed() }, enabled = session.index > 0,
                modifier = Modifier.semantics { contentDescription = previousLabel },
            ) { Text("‹") }
            OutlinedButton(
                onClick = { session.next(); vm.changed() }, enabled = session.index < section.items.lastIndex,
                modifier = Modifier.semantics { contentDescription = nextLabel },
            ) { Text("›") }
            Box(Modifier.weight(1f))
            Button(onClick = { confirmEnd = true }) { Text(stringResource(if (lastSection) R.string.action_finish else R.string.exam_end_section)) }
        }
    }
    if (confirmEnd) {
        val unanswered = section.items.count { session.choiceFor(it.item.id) == null }
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(if (lastSection) R.string.exam_finish_title else R.string.exam_end_section_title)) },
            text = {
                Text(
                    (if (unanswered > 0) stringResource(R.string.exam_unanswered_warning, unanswered) + " " else "") +
                        stringResource(if (lastSection) R.string.exam_will_score else R.string.exam_no_return),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    voices.stop()
                    if (lastSection) vm.submit() else { session.nextSection(); vm.changed() }
                }) { Text(stringResource(if (lastSection) R.string.action_finish else R.string.exam_end_section)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.exam_keep_working)) } },
        )
    }
}

@Composable
private fun ResultView(vm: ExamRunViewModel, result: ExamResult, onReview: (String) -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScoringView(result.form.exam, result.summary, result.scoring)
        val saveError = vm.saveError
        if (saveError != null) {
            ErrorState(stringResource(R.string.exam_save_failed, saveError), onRetry = vm::retrySave)
        } else {
            Text(stringResource(if (vm.savedId != null) R.string.exam_saved else R.string.exam_saving), style = MaterialTheme.typography.bodySmall)
        }
        val refs = result.missedRefs.filter { it.startsWith("g:") || it.startsWith("v:") }
        if (refs.isNotEmpty()) {
            val added = vm.added
            Button(onClick = vm::addMissedToSrs, enabled = added == null) {
                Text(stringResource(R.string.exam_add_missed, refs.size))
            }
            added?.let {
                Text(
                    if (it == 0) stringResource(R.string.exam_nothing_to_add) else stringResource(R.string.exam_added_items, it),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        vm.savedId?.let { id -> OutlinedButton(onClick = { onReview(id) }) { Text(stringResource(R.string.exam_review_answers)) } }
        TextButton(onClick = onExit) { Text(stringResource(R.string.action_done)) }
    }
}
