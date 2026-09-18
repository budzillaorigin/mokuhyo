package app.tsumugi.android.features.exams

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

class ExamRunViewModel(app: Application, private val spec: ExamSpec) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    private val seed = System.currentTimeMillis()

    var preview by mutableStateOf<ExamForm?>(null)
        private set
    var loaded by mutableStateOf(false)
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
        viewModelScope.launch {
            preview = create()?.form
            loaded = true
        }
    }

    private suspend fun create(): ExamSession? {
        val exams = graph.exams()
        return when (spec) {
            is ExamSpec.JlptMock -> exams.jlptMock(spec.level, seed)
            is ExamSpec.JlptSection -> exams.jlptSection(spec.level, spec.sectionId, seed)
            is ExamSpec.JlptType -> exams.jlptTypeDrill(spec.level, spec.type, seed)
            is ExamSpec.Dlpt -> exams.dlpt(spec.exam, spec.minutes, seed)
        }
    }

    /** Builds the same form again (same seed) so the clock starts now, not when the preview was made. */
    fun start() = viewModelScope.launch {
        session = create()
        version++
    }

    fun changed() {
        version++
    }

    /** Once a second: advances the section when its time runs out; submits after the last one. */
    fun tick() {
        val s = session ?: return
        s.tick()
        if (s.finished && result == null) submit() else version++
    }

    fun submit() {
        val s = session ?: return
        if (result != null) return
        val r = s.submit()
        result = r
        version++
        viewModelScope.launch { savedId = graph.exams().save(r) }
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
            title = { Text("Leave the exam?") },
            text = { Text("This attempt won't be scored or saved.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onExit() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Stay") } },
        )
    }
    when {
        !vm.loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        result != null -> ResultView(vm, result, onReview, onExit)
        session != null -> Runner(vm, session, spec)
        else -> Preview(vm, spec)
    }
}

@Composable
private fun Preview(vm: ExamRunViewModel, spec: ExamSpec) {
    val form = vm.preview
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(spec.title, style = MaterialTheme.typography.headlineSmall)
        if (form == null) {
            Notice(EXAM_PACK_MISSING)
            return@Column
        }
        if (form.isEmpty) {
            Notice("The installed item banks have no items for this. Import an item bank from Me → Import & export.")
            return@Column
        }
        form.sections.forEach { s ->
            Text("• ${s.title}: ${s.items.size} items" + (s.minutes?.let { " · $it min" } ?: " · untimed"))
        }
        if (form.shortfalls.isNotEmpty()) {
            Notice(
                "The bank is short of items, so this form is smaller than the real test:\n" +
                    form.shortfalls.joinToString("\n") { "• ${typeLabel(it.type)}: ${it.got} of ${it.wanted}" },
            )
        }
        if (spec.strict) {
            Text(
                "Strict mode: each section runs on its real clock without pausing, listening audio plays once, and " +
                    "dictionary lookups are off until you finish. Closed sections can't be reopened.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (form.items.any { it.item.aiGenerated }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AiBadge()
                Text("Some items are AI-drafted and not yet reviewed.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(onClick = { vm.start() }) { Text("Start") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Runner(vm: ExamRunViewModel, session: ExamSession, spec: ExamSpec) {
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var confirmEnd by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        while (!session.finished) {
            delay(1_000)
            vm.tick()
        }
        vm.submit()
    }
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
                    Text(section.title, style = MaterialTheme.typography.titleMedium.japanese())
                    Text(
                        "Section ${session.sectionIndex + 1} of ${session.form.sections.size} · answered ${session.answeredCount}/${session.totalCount}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                session.remainingMs()?.let { ms ->
                    val s = ms / 1000
                    Text(
                        "%d:%02d".format(s / 60, s % 60),
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
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = when {
                            current -> MaterialTheme.colorScheme.primary
                            answered -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        modifier = Modifier.size(48.dp).clickable(onClickLabel = "Go to question ${i + 1}") { session.goTo(i); vm.changed() },
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
                JlptItemType.of(item.type)?.let { "${it.title} · ${it.english}" } ?: formItem.typeTitle,
                style = MaterialTheme.typography.labelLarge.japanese(),
                color = MaterialTheme.colorScheme.primary,
            )
            if (passage != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (passage.title.isNotBlank()) Text(passage.title, style = MaterialTheme.typography.titleSmall.japanese())
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
                            scope.launch { voices.sayAll(script.map { it.text to it.voice }) }
                        },
                        enabled = canPlay,
                    ) { Text("▶ Play audio") }
                    Text(
                        if (spec.strict) (if (canPlay) "Plays once" else "Already played") else "Replay as often as you like",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            ExamText(item.stem, style = MaterialTheme.typography.titleMedium)
            if (item.aiGenerated) AiBadge()
            val chosen = session.choiceFor(item.id)
            item.choices.forEachIndexed { i, c ->
                Card(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = "Choose ${i + 1}") { session.choose(i); vm.changed() },
                    colors = CardDefaults.cardColors(
                        containerColor = if (chosen == i) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    ),
                    border = CardDefaults.outlinedCardBorder(),
                ) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chosen == i, onClick = { session.choose(i); vm.changed() })
                        Text("${i + 1}  ", style = MaterialTheme.typography.bodyLarge)
                        ExamText(c, Modifier.weight(1f))
                    }
                }
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { session.previous(); vm.changed() }, enabled = session.index > 0,
                modifier = Modifier.semantics { contentDescription = "Previous question" },
            ) { Text("‹") }
            OutlinedButton(
                onClick = { session.next(); vm.changed() }, enabled = session.index < section.items.lastIndex,
                modifier = Modifier.semantics { contentDescription = "Next question" },
            ) { Text("›") }
            Box(Modifier.weight(1f))
            Button(onClick = { confirmEnd = true }) { Text(if (lastSection) "Finish" else "End section") }
        }
    }
    if (confirmEnd) {
        val unanswered = section.items.count { session.choiceFor(it.item.id) == null }
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(if (lastSection) "Finish the exam?" else "End this section?") },
            text = {
                Text(
                    (if (unanswered > 0) "$unanswered unanswered in this section (they count as wrong). " else "") +
                        if (lastSection) "Your answers will be scored." else "You can't come back to this section.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    voices.stop()
                    if (lastSection) vm.submit() else { session.nextSection(); vm.changed() }
                }) { Text(if (lastSection) "Finish" else "End section") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Keep working") } },
        )
    }
}

@Composable
private fun ResultView(vm: ExamRunViewModel, result: ExamResult, onReview: (String) -> Unit, onExit: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ScoringView(result.form.exam, result.summary, result.scoring)
        Text(if (vm.savedId != null) "Saved to your exam history." else "Saving…", style = MaterialTheme.typography.bodySmall)
        val refs = result.missedRefs.filter { it.startsWith("g:") || it.startsWith("v:") }
        if (refs.isNotEmpty()) {
            val added = vm.added
            Button(onClick = vm::addMissedToSrs, enabled = added == null) {
                Text("Add missed grammar & words to SRS (${refs.size})")
            }
            added?.let {
                Text(
                    if (it == 0) "Nothing new to add (already in your reviews, or the packs aren't installed)." else "Added $it items to your reviews.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        vm.savedId?.let { id -> OutlinedButton(onClick = { onReview(id) }) { Text("Review answers") } }
        TextButton(onClick = onExit) { Text("Done") }
    }
}
