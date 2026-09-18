package app.tsumugi.android.features.practice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.platform.AudioFilePlayer
import app.tsumugi.android.platform.AudioRecorder
import app.tsumugi.android.platform.ClipPlayer
import app.tsumugi.android.platform.FileClipPlayer
import app.tsumugi.android.platform.Recording
import app.tsumugi.android.platform.SpeechResult
import app.tsumugi.android.platform.TtsClipPlayer
import app.tsumugi.android.platform.rememberMicPermission
import app.tsumugi.android.platform.rememberSpeechInput
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.platform.storeRecording
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.readable
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.ReferenceKind
import app.tsumugi.speech.PronunciationReport
import app.tsumugi.speech.ShadowingReport
import app.tsumugi.study.ShadowingSentence
import app.tsumugi.study.TodayBlockKind
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import app.tsumugi.recordings.Recording as StoredRecording

/**
 * The Today shadowing block (G-01): hear each sentence ([ClipPlayer]: TTS now, an audio-pack clip later), record
 * yourself, then see the pronunciation analysis and the shadowing comparison (timing and intonation against the
 * reference). The recording is kept (G-03) for side-by-side playback. Finishing marks the block done.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShadowingScreen(sentences: List<ShadowingSentence>, onDone: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val voices = rememberVoices()
    val input = rememberSpeechInput()
    val scope = rememberCoroutineScope()
    var index by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<PronunciationReport?>(null) }
    var shadow by remember { mutableStateOf<ShadowingReport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf<StoredRecording?>(null) }
    var finishing by remember { mutableStateOf(false) }

    if (sentences.isEmpty()) {
        Notice(stringResource(R.string.shadow_empty), Modifier.padding(16.dp))
        return
    }
    val sentence = sentences.getOrNull(index)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (sentence == null) {
            Text(stringResource(R.string.shadow_done, sentences.size), style = MaterialTheme.typography.titleMedium)
            Button(onClick = {
                finishing = true
                scope.launch {
                    runCatching { graph.markTodayBlockDone(TodayBlockKind.SHADOWING) }
                    onDone()
                }
            }, enabled = !finishing) { Text(stringResource(R.string.action_done)) }
            return@Column
        }
        val clip: ClipPlayer = remember(sentence.japanese) { TtsClipPlayer(context, voices, sentence.japanese) }
        LaunchedEffect(index) {
            report = null; shadow = null; message = null; saved = null
        }
        Text(stringResource(R.string.shadow_progress, index + 1, sentences.size), style = MaterialTheme.typography.labelLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                JaText(sentence.japanese, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                Text(sentence.english, style = MaterialTheme.typography.bodyMedium)
                JaText(sentence.pointTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (sentence.aiGenerated) AiBadge()
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { clip.play(speed) } }) { PlayLabel(stringResource(R.string.shadow_listen)) }
            listOf(0.8f, 1f).forEach { s -> FilterChip(speed == s, { speed = s }, { Text("×$s") }) }
        }
        Text(stringResource(R.string.shadow_hint), style = MaterialTheme.typography.bodySmall)
        MicButton(
            input,
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = !busy,
            onError = { message = it },
            onResult = { result: SpeechResult ->
                message = result.error
                if (result.audio.isEmpty) {
                    message = listOfNotNull(result.error, context.getString(R.string.pron_no_audio)).joinToString("\n")
                    return@MicButton
                }
                busy = true
                scope.launch {
                    try {
                        report = graph.pronunciation.analyze(sentence.japanese, result.transcript.ifBlank { null }, result.audio.samples, result.analyzerSegments)
                        val reference = clip.pcm()
                        shadow = reference?.let { graph.pronunciation.shadowing(it, result.audio.samples) }
                        if (reference == null) message = context.getString(R.string.shadow_no_reference)
                        saved = graph.storeRecording(result.audio, RecordingKind.SENTENCE, sentence.japanese, clip.reference)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = context.getString(R.string.pron_failed, e.readable())
                    } finally {
                        busy = false
                    }
                }
            },
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        shadow?.let { ShadowingView(it) }
        report?.let { ReportView(it) }
        saved?.let { rec ->
            SideBySideRow(rec.let { graph.recordings.pathOf(it) }, clip)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (index > 0) OutlinedButton(onClick = { index-- }) { Text(stringResource(R.string.action_back)) }
            Button(onClick = { index++ }) { Text(stringResource(if (index < sentences.lastIndex) R.string.action_next else R.string.action_finish)) }
        }
    }
}

/** Timing and intonation against the reference (heuristic, labeled as such). */
@Composable
fun ShadowingView(r: ShadowingReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.shadow_compare_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.shadow_scores, r.overall, r.timingScore, r.intonationScore), style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(progress = { r.overall / 100f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.shadow_length, "%.2f".format(r.durationRatio)), style = MaterialTheme.typography.bodySmall)
            r.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.pron_heuristic), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Side-by-side playback (G-03): mine, the model, or one after the other. */
@Composable
fun SideBySideRow(minePath: String, reference: ClipPlayer?, referenceText: String? = null) {
    val scope = rememberCoroutineScope()
    val voices = if (reference == null && referenceText != null) rememberVoices() else null
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { scope.launch { AudioFilePlayer.play(minePath) } }) { PlayLabel(stringResource(R.string.rec_play_mine)) }
        if (reference != null || voices != null) {
            OutlinedButton(onClick = { scope.launch { reference?.play() ?: voices?.say(referenceText!!) } }) { PlayLabel(stringResource(R.string.rec_play_model)) }
            OutlinedButton(onClick = {
                scope.launch {
                    AudioFilePlayer.play(minePath)
                    reference?.play() ?: voices?.say(referenceText!!)
                }
            }) { PlayLabel(stringResource(R.string.rec_play_both)) }
        }
    }
}

/**
 * Tap to record, tap to stop (G-03 "add my recording"): raw 16 kHz audio, no transcription. [onRecorded] gets
 * the audio; the caller stores it.
 */
@Composable
fun RecordButton(onRecorded: (Recording) -> Unit, modifier: Modifier = Modifier, onError: (String) -> Unit = {}) {
    val permission = rememberMicPermission()
    var recorder by remember { mutableStateOf<AudioRecorder?>(null) }
    DisposableEffect(Unit) { onDispose { recorder?.stop() } }
    val recording = recorder != null
    val label = when {
        !permission.granted -> stringResource(R.string.mic_allow)
        recording -> stringResource(R.string.rec_stop)
        else -> stringResource(R.string.rec_add_mine)
    }
    Button(
        onClick = {
            when {
                !permission.granted -> permission.request()
                recording -> {
                    val audio = recorder!!.stop()
                    recorder = null
                    if (audio.durationMs < 300) onError("") else onRecorded(audio)
                }
                else -> {
                    val r = AudioRecorder()
                    val error = r.start()
                    if (error != null) onError(error) else recorder = r
                }
            }
        },
        modifier = modifier,
    ) { Text((if (recording) "● " else "🎤 ") + label) }
}

/**
 * The learner's recordings of one thing (an SRS item or a sentence) with side-by-side playback against
 * [reference], plus "add my recording". Used on item pages (G-03).
 */
@Composable
fun RecordingsPanel(
    kind: RecordingKind,
    ref: String,
    referenceText: String,
    modifier: Modifier = Modifier,
    /** Offered per recording: turn it into a self-recorded audio card (G-03, `PersonalCards.addAudioSide`). */
    onUseAsCard: (suspend (StoredRecording) -> Unit)? = null,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var list by remember(ref) { mutableStateOf<List<StoredRecording>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        list = runCatching { graph.recordings.recordingsFor(kind, ref) }.getOrElse { error = it.readable(); emptyList() }
    }
    LaunchedEffect(ref) { reload() }
    val reference = remember(referenceText) { TtsClipPlayer(context, voices, referenceText) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.rec_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        error?.let { ErrorState(it, onRetry = { error = null; scope.launch { reload() } }) }
        list.forEach { rec ->
            Text(recordingLabel(rec), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SideBySideRow(graph.recordings.pathOf(rec), reference)
                TextButton(onClick = { scope.launch { graph.recordings.delete(rec.id); reload() } }) { Text(stringResource(R.string.action_delete)) }
            }
            if (onUseAsCard != null) {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { onUseAsCard(rec) }
                            .onSuccess { error = null; note = context.getString(R.string.rec_card_added) }
                            .onFailure { error = it.readable() }
                    }
                }) { Text(stringResource(R.string.rec_make_card)) }
            }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        RecordButton(
            onRecorded = { audio ->
                scope.launch {
                    runCatching { graph.storeRecording(audio, kind, ref, reference.reference) }
                        .onFailure { error = it.readable() }
                    reload()
                }
            },
            onError = { error = it.ifBlank { context.getString(R.string.rec_too_short) } },
        )
    }
}

fun recordingLabel(rec: StoredRecording): String =
    "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(rec.createdAt))} · ${"%.1f".format(rec.durationMs / 1000.0)} s"

/** Me → My recordings (G-03): everything recorded on this device, with side-by-side playback. */
@Composable
fun RecordingsScreen() {
    val graph = rememberGraph()
    val context = LocalContext.current
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<StoredRecording>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var syncOn by remember { mutableStateOf(false) }
    suspend fun reload() {
        runCatching { graph.recordings.all() }.onSuccess { list = it; error = null }.onFailure { error = it.readable() }
        syncOn = runCatching { graph.recordingSync.isEnabled() }.getOrDefault(false)
    }
    LaunchedEffect(Unit) { reload() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.rec_intro), style = MaterialTheme.typography.bodySmall)
        ListItem(
            modifier = Modifier.clickable { scope.launch { graph.recordingSync.setEnabled(!syncOn); reload() } },
            headlineContent = { Text(stringResource(R.string.rec_sync)) },
            supportingContent = { Text(stringResource(R.string.rec_sync_hint)) },
            trailingContent = { Switch(syncOn, onCheckedChange = null) },
        )
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { scope.launch { reload() } }) }
        val l = list
        when {
            l == null && error == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            l != null && l.isEmpty() -> Text(stringResource(R.string.rec_none), style = MaterialTheme.typography.bodyMedium)
            l != null -> {
                Text(stringResource(R.string.rec_total, l.size, "%.1f".format(l.sumOf { it.sizeBytes } / 1_048_576.0)), style = MaterialTheme.typography.labelLarge)
                l.forEach { rec ->
                    var side by remember(rec.id) { mutableStateOf<ClipPlayer?>(null) }
                    LaunchedEffect(rec.id) {
                        val s = runCatching { graph.recordings.sideBySide(rec.id) }.getOrNull()
                        side = when {
                            s?.referencePath != null -> FileClipPlayer(context, s.referencePath!!, s.reference!!)
                            s?.reference?.kind == ReferenceKind.TTS -> TtsClipPlayer(context, voices, s.reference!!.value)
                            else -> null
                        }
                    }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(rec.kind.name.lowercase().replaceFirstChar { it.uppercase() } + (rec.ref?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(recordingLabel(rec), style = MaterialTheme.typography.bodySmall)
                            SideBySideRow(graph.recordings.pathOf(rec), side)
                            TextButton(onClick = { scope.launch { graph.recordings.delete(rec.id); reload() } }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
}
