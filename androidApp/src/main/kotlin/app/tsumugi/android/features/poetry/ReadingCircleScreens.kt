package app.tsumugi.android.features.poetry

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.AudioFilePlayer
import app.tsumugi.android.platform.AudioRecorder
import app.tsumugi.android.platform.WavFile
import app.tsumugi.android.platform.rememberMicPermission
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.readable
import app.tsumugi.poetry.CircleHelp
import app.tsumugi.poetry.CircleReading
import app.tsumugi.poetry.CircleSession
import app.tsumugi.poetry.CircleTextDetail
import app.tsumugi.poetry.CircleTextSummary
import app.tsumugi.poetry.ReadingCircle
import app.tsumugi.reader.ReaderDocumentSummary
import app.tsumugi.recordings.Recording
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class CircleHome(
    val texts: List<CircleTextSummary>,
    val sessions: List<CircleSession>,
    val library: List<ReaderDocumentSummary>,
)

/**
 * The solo reading circle (BRIEF_V2 §6.14, D-278, D-297): pick a pack text or a library document; saved sessions
 * resume where they stopped.
 */
@Composable
fun ReadingCircleScreen(onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var home by remember { mutableStateOf<CircleHome?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var showLibrary by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<CircleSession?>(null) }
    LaunchedEffect(attempt) {
        error = null
        runCatching {
            CircleHome(
                graph.readingCircle.texts(), graph.readingCircle.sessions(),
                runCatching { graph.reader.documents() }.getOrDefault(emptyList()),
            )
        }.onSuccess { home = it }.onFailure { error = it.readable() }
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            text = { Text(stringResource(R.string.rc_delete_confirm, s.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { runCatching { graph.readingCircle.delete(s) }.onFailure { error = it.readable() }; attempt++ }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.rc_intro), style = MaterialTheme.typography.bodyMedium)
        val h = home
        when {
            error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ })
            h == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                if (h.sessions.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.rc_sessions))
                    h.sessions.forEach { s ->
                        ListItem(
                            modifier = Modifier.clickable { onOpen(s.textId) },
                            headlineContent = { JaText(s.title, maxLines = 1) },
                            supportingContent = {
                                Text(
                                    if (s.finished) stringResource(R.string.rc_session_finished, s.sentenceCount)
                                    else stringResource(R.string.rc_session_progress, s.doneCount, s.sentenceCount),
                                )
                            },
                            trailingContent = { TextButton(onClick = { confirmDelete = s }) { Text(stringResource(R.string.action_delete)) } },
                        )
                    }
                }
                SectionTitle(stringResource(R.string.rc_texts))
                if (h.texts.isEmpty()) Notice(stringResource(R.string.rc_pack_missing))
                h.texts.forEach { t ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(t.id) },
                        headlineContent = { JaText("${t.title}　${t.author}") },
                        supportingContent = {
                            Text(listOf(t.titleEn, t.level, stringResource(R.string.rc_sentences, t.sentenceCount)).filter { it.isNotBlank() }.joinToString(" · "))
                        },
                    )
                }
                if (h.library.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.rc_library))
                    if (!showLibrary) {
                        OutlinedButton(onClick = { showLibrary = true }) { Text(stringResource(R.string.rc_library_show, h.library.size)) }
                    } else {
                        h.library.forEach { d ->
                            ListItem(
                                modifier = Modifier.clickable { onOpen(ReadingCircle.DOC + d.id) },
                                headlineContent = { JaText(d.title, maxLines = 1) },
                                supportingContent = d.author?.let { { JaText(it) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** What a sentence's recorder is doing. */
private enum class RecTarget { READ, EXPLAIN }

/**
 * One session: the current sentence (with ruby), record reading it aloud, explain it in English (typed or recorded),
 * then mark it done. Every sentence has dictionary and grammar help, and its recordings can be played back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CircleSessionScreen(textId: String, onOpenEntry: (Long) -> Unit, onOpenGrammar: (String) -> Unit) {
    val graph = rememberGraph()
    val circle = graph.readingCircle
    val scope = rememberCoroutineScope()
    var reading by remember(textId) { mutableStateOf<CircleReading?>(null) }
    var detail by remember(textId) { mutableStateOf<CircleTextDetail?>(null) }
    var session by remember(textId) { mutableStateOf<CircleSession?>(null) }
    var loaded by remember(textId) { mutableStateOf(false) }
    var error by remember(textId) { mutableStateOf<String?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(textId, attempt) {
        error = null
        runCatching {
            reading = circle.reading(textId)
            detail = if (textId.startsWith(ReadingCircle.DOC)) null else graph.poetry()?.circleText(textId)
            session = if (reading != null) circle.start(textId) else null
        }.onFailure { error = it.readable() }
        loaded = true
    }
    fun act(block: suspend () -> CircleSession) {
        scope.launch {
            actionError = null
            runCatching { block() }.onSuccess { session = it }.onFailure { actionError = it.readable() }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val r = reading
        val s = session
        when {
            error != null -> { ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ }); return@Column }
            !loaded -> { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            r == null -> { Notice(stringResource(R.string.rc_text_missing)); return@Column }
            s == null -> { Notice(stringResource(R.string.rc_text_empty)); return@Column }
        }
        r!!; s!!
        val idx = s.position.coerceIn(0, r.sentences.size - 1)
        val sentence = r.sentences[idx]
        val entry = s.entry(idx)
        JaText(r.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        detail?.let { d ->
            if (d.author.isNotBlank()) JaText(d.author, style = MaterialTheme.typography.titleSmall)
            if (d.summaryEn.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rc_summary), style = MaterialTheme.typography.labelMedium)
                    if (d.isAiGenerated) AiBadge()
                }
                Text(d.summaryEn, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            if (s.finished) stringResource(R.string.rc_session_finished, s.sentenceCount) else stringResource(R.string.rc_session_progress, s.doneCount, s.sentenceCount),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
        )
        LinearProgressIndicator(progress = { s.doneCount.toFloat() / s.sentenceCount.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())

        // The sentence and navigation.
        var showRuby by remember { mutableStateOf(true) }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.rc_sentence_n, idx + 1, r.sentences.size), style = MaterialTheme.typography.labelMedium)
                    if (entry.done) Tag(stringResource(R.string.rc_done))
                }
                RubyText(sentence.text.trim(), sentence.start + (sentence.text.length - sentence.text.trimStart().length), r.ruby, showRuby, MaterialTheme.typography.titleLarge)
                if (r.ruby.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(showRuby, { showRuby = it })
                        Text(stringResource(R.string.po_show_ruby), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { act { circle.moveTo(s, idx - 1) } }, enabled = idx > 0) { Text(stringResource(R.string.rc_previous)) }
            OutlinedButton(onClick = { act { circle.moveTo(s, idx + 1) } }, enabled = idx < r.sentences.size - 1) { Text(stringResource(R.string.rc_next_sentence)) }
        }
        actionError?.let { Notice(stringResource(R.string.error_loading, it)) }

        // 1. Read aloud.
        SectionTitle(stringResource(R.string.rc_step_read))
        Recorder(RecTarget.READ, recorded = entry.read != null, key = "${s.id}/$idx/read") { pending, ms -> act { circle.attachReading(s, idx, pending, ms) } }

        // 2. Explain in English.
        SectionTitle(stringResource(R.string.rc_step_explain))
        var explanation by remember(s.id, idx) { mutableStateOf(entry.explain) }
        OutlinedTextField(
            explanation, { explanation = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp),
            label = { Text(stringResource(R.string.rc_explain_hint)) },
        )
        OutlinedButton(onClick = { act { circle.explain(s, idx, explanation) } }, enabled = explanation.trim() != entry.explain) {
            Text(stringResource(R.string.rc_save_explanation))
        }
        Recorder(RecTarget.EXPLAIN, recorded = entry.explainRec != null, key = "${s.id}/$idx/explain") { pending, ms -> act { circle.attachExplanationRecording(s, idx, pending, ms) } }

        // 3. Done.
        val ready = entry.read != null && (entry.explained || explanation.isNotBlank())
        Button(
            onClick = {
                act {
                    val withText = if (explanation.trim() != entry.explain && explanation.isNotBlank()) circle.explain(s, idx, explanation) else s
                    circle.complete(withText, idx)
                }
            },
            enabled = ready && !entry.done,
        ) { Text(stringResource(R.string.rc_complete)) }
        if (!ready) Text(stringResource(R.string.rc_complete_hint), style = MaterialTheme.typography.bodySmall)

        SentenceRecordings(s, idx)
        HorizontalDivider()
        HelpSection(r, idx, onOpenEntry, onOpenGrammar)
        detail?.work?.let { SourceNotes(it) }
    }
}

/** Mic button for one recording: start → stop → stored as WAV through the recordings store, then attached. */
@Composable
private fun Recorder(target: RecTarget, recorded: Boolean, key: String, onRecorded: suspend (app.tsumugi.recordings.PendingRecording, Long) -> Unit) {
    val graph = rememberGraph()
    val mic = rememberMicPermission()
    val scope = rememberCoroutineScope()
    var recorder by remember(key) { mutableStateOf<AudioRecorder?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var saving by remember(key) { mutableStateOf(false) }
    DisposableEffect(key) { onDispose { recorder?.stop(); recorder = null } }
    if (!mic.granted) {
        Notice(stringResource(R.string.rc_mic_needed), actionLabel = stringResource(R.string.rc_mic_allow), onAction = mic.request)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val active = recorder
        if (active == null) {
            Button(onClick = {
                val r = AudioRecorder(maxSeconds = 120)
                val problem = r.start()
                if (problem != null) error = problem else { error = null; recorder = r }
            }, enabled = !saving) {
                Text(
                    stringResource(
                        when {
                            target == RecTarget.READ && recorded -> R.string.rc_record_read_again
                            target == RecTarget.READ -> R.string.rc_record_read
                            recorded -> R.string.rc_record_explain_again
                            else -> R.string.rc_record_explain
                        },
                    ),
                )
            }
        } else {
            Button(onClick = {
                val audio = active.stop()
                recorder = null
                if (audio.isEmpty) return@Button
                saving = true
                scope.launch {
                    runCatching {
                        val pending = graph.recordings.newRecording("wav")
                        withContext(Dispatchers.IO) { WavFile.write(File(pending.path), audio.pcm) }
                        onRecorded(pending, audio.durationMs)
                    }.onFailure { error = it.readable() }
                    saving = false
                }
            }) { Text(stringResource(R.string.rc_stop)) }
            Text(stringResource(R.string.rc_recording), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (recorded && active == null) Tag(stringResource(R.string.rc_recorded))
    }
    if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Notice(it) }
}

/** The recordings kept for this sentence, newest first, each with ▶. */
@Composable
private fun SentenceRecordings(session: CircleSession, idx: Int) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Recording>>(emptyList()) }
    LaunchedEffect(session.updatedAt, idx) { list = runCatching { graph.readingCircle.recordingsOf(session, idx) }.getOrDefault(emptyList()) }
    if (list.isEmpty()) return
    SectionTitle(stringResource(R.string.rc_recordings))
    list.forEach { rec ->
        val explain = rec.ref?.endsWith("/explain") == true
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (explain) R.string.rc_rec_explain else R.string.rc_rec_read, "%.1f".format(rec.durationMs / 1000.0)),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { scope.launch { runCatching { AudioFilePlayer.play(graph.recordings.pathOf(rec)) } } }) { PlayLabel(stringResource(R.string.rc_play)) }
        }
    }
}

/** Dictionary words (tap to open) and grammar points of the sentence, plus a TTS read of it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HelpSection(reading: CircleReading, idx: Int, onOpenEntry: (Long) -> Unit, onOpenGrammar: (String) -> Unit) {
    val graph = rememberGraph()
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var open by remember(idx) { mutableStateOf(false) }
    var help by remember(idx) { mutableStateOf<CircleHelp?>(null) }
    var loaded by remember(idx) { mutableStateOf(false) }
    var error by remember(idx) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.rc_help_hide else R.string.rc_help)) }
        TextButton(onClick = { scope.launch { runCatching { voices.say(reading.sentences[idx].text) } } }) { PlayLabel(stringResource(R.string.rc_listen)) }
    }
    if (!open) return
    LaunchedEffect(idx, attempt) {
        error = null
        runCatching { graph.readingCircle.help(reading, idx) }.onSuccess { help = it }.onFailure { error = it.readable() }
        loaded = true
    }
    val h = help
    when {
        error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ })
        !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
        h == null -> Notice(stringResource(R.string.rc_help_no_dictionary))
        else -> {
            Text(stringResource(R.string.rc_help_words), style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                h.sentence.tokens.filter { it.surface.isNotBlank() && it.surface.any { c -> c.isLetter() } }.forEach { t ->
                    AssistChip(
                        onClick = { t.entryId?.let(onOpenEntry) },
                        enabled = t.entryId != null,
                        label = {
                            Column {
                                JaText(t.surface, style = MaterialTheme.typography.bodyLarge)
                                val lemma = t.dictionaryForm?.takeIf { it != t.surface }
                                val extra = listOfNotNull(lemma, t.reading?.takeIf { it != t.surface }).joinToString(" · ")
                                if (extra.isNotBlank()) JaText(extra, style = MaterialTheme.typography.labelSmall)
                            }
                        },
                    )
                }
            }
            if (h.grammar.isNotEmpty()) {
                Text(stringResource(R.string.rc_help_grammar), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    h.grammar.forEach { (id, title) -> AssistChip(onClick = { onOpenGrammar(id) }, label = { JaText(title) }) }
                }
            }
        }
    }
}
