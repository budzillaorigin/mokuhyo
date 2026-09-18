package app.tsumugi.android.features.practice

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.tsumugi.android.R
import app.tsumugi.android.app.displayName
import app.tsumugi.android.platform.ClipCutter
import app.tsumugi.android.platform.MediaPcmSource
import app.tsumugi.android.platform.mediaHash
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.media.Cue
import app.tsumugi.media.DualCue
import app.tsumugi.media.GeneratedSubtitles
import app.tsumugi.media.MediaPlayback
import app.tsumugi.media.QuizMode
import app.tsumugi.media.QuizResult
import app.tsumugi.media.SubtitleProgress
import app.tsumugi.media.SubtitleQuiz
import app.tsumugi.media.Subtitles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * What the media studio plays: [uri], a content key for caches and clips ([key], the shared MediaHash), and how
 * to make Japanese subtitles when there are none ([generate]: on-device Whisper through the shared generator).
 */
class StudioSource(
    val uri: Uri,
    val title: String,
    val key: suspend () -> String,
    val generate: suspend (onProgress: (SubtitleProgress) -> Unit) -> GeneratedSubtitles,
    val startPositionMs: Long = 0,
    val onPosition: (suspend (Long) -> Unit)? = null,
)

/**
 * Media player for the learner's own files (BRIEF §5.9, G-04): a local video or audio file plus Japanese subtitles
 * (.srt/.vtt, or generated on device) and optional English ones. Media3 ExoPlayer, dual subtitles, speed 0.7–1.2×,
 * an A-B loop, save a line as a listening card with its audio, and the hide-subtitle quiz.
 */
@Composable
fun MediaPlayerScreen(onLookup: (String) -> Unit, onPodcasts: () -> Unit) {
    val context = LocalContext.current
    val graph = rememberGraph()
    var media by rememberSaveable { mutableStateOf<String?>(null) }
    var jaSubs by rememberSaveable { mutableStateOf<String?>(null) }
    var enSubs by rememberSaveable { mutableStateOf<String?>(null) }
    var jaCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var enCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Keep read access across restarts of the screen (the URI is saved in rememberSaveable).
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        media = uri.toString()
    }
    val jaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { jaSubs = it.toString() } }
    val enPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { enSubs = it.toString() } }

    LaunchedEffect(jaSubs, enSubs) {
        error = null
        val ja = jaSubs?.let { runCatching { Subtitles.parse(readText(context, Uri.parse(it))) }.onFailure { e -> error = context.getString(R.string.media_ja_failed, e.message.orEmpty()) }.getOrNull() }.orEmpty()
        val en = enSubs?.let { runCatching { Subtitles.parse(readText(context, Uri.parse(it))) }.onFailure { e -> error = context.getString(R.string.media_en_failed, e.message.orEmpty()) }.getOrNull() }.orEmpty()
        if (jaSubs != null && ja.isEmpty() && error == null) error = context.getString(R.string.media_no_cues)
        jaCues = ja
        enCues = en
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { mediaPicker.launch(arrayOf("video/*", "audio/*")) }) { Text(stringResource(if (media == null) R.string.media_choose else R.string.media_change)) }
            OutlinedButton(onClick = onPodcasts) { Text(stringResource(R.string.title_podcasts)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { jaPicker.launch(arrayOf("*/*")) }) { Text(stringResource(if (jaSubs == null) R.string.media_ja_subs else R.string.media_ja_subs_ok)) }
            OutlinedButton(onClick = { enPicker.launch(arrayOf("*/*")) }) { Text(stringResource(if (enSubs == null) R.string.media_en_subs else R.string.media_en_subs_ok)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val uriString = media
        if (uriString == null) {
            Notice(stringResource(R.string.media_intro))
        } else {
            val uri = Uri.parse(uriString)
            val source = remember(uriString) {
                val title = runCatching { displayName(context, uri) }.getOrDefault(uri.lastPathSegment.orEmpty())
                var cachedKey: String? = null
                val key: suspend () -> String = { cachedKey ?: mediaHash(context, uri).also { cachedKey = it } }
                StudioSource(uri, title, key, generate = { onProgress -> graph.subtitles.generate(key(), MediaPcmSource(context, uri), "ja", onProgress) })
            }
            MediaStudio(source, jaCues, enCues, onLookup)
        }
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The player with subtitles, speed, loop, clip saving, subtitle generation and the listening quiz. */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaStudio(source: StudioSource, loadedJa: List<Cue>, enCues: List<Cue>, onLookup: (String) -> Unit) {
    val context = LocalContext.current
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val player = remember(source.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(source.uri))
            prepare()
            if (source.startPositionMs > 0) seekTo(source.startPositionMs)
        }
    }
    var playing by remember { mutableStateOf(false) }
    var hasVideo by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(source.startPositionMs) }
    var speed by remember { mutableStateOf(MediaPlayback.SPEED_DEFAULT) }
    var loop by remember { mutableStateOf<DualCue?>(null) }
    var showJa by remember { mutableStateOf(true) }
    var showEn by remember { mutableStateOf(true) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onVideoSizeChanged(videoSize: VideoSize) { hasVideo = videoSize.width > 0 && videoSize.height > 0 }
            override fun onPlayerError(error: PlaybackException) { playerError = error.message ?: error.errorCodeName }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            val last = player.currentPosition
            // The screen's scope is already cancelled here: save the resume position on the app scope.
            source.onPosition?.let { save -> (context.applicationContext as app.tsumugi.android.TsumugiApplication).appScope.launch { runCatching { save(last) } } }
            player.release()
        }
    }

    // Subtitles: loaded from a file, else generated on device (cached by content key, D-113).
    var generated by remember(source.uri) { mutableStateOf<GeneratedSubtitles?>(null) }
    var progress by remember { mutableStateOf<SubtitleProgress?>(null) }
    var genJob by remember { mutableStateOf<Job?>(null) }
    var genError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source.uri) {
        generated = runCatching { graph.subtitles.cached(source.key()) }.getOrNull()
    }
    fun generate() {
        genError = null
        progress = SubtitleProgress(0, 0)
        genJob = scope.launch {
            try {
                generated = source.generate { p -> progress = p }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                genError = e.readable()
            } finally {
                progress = null
                genJob = null
            }
        }
    }
    val jaCues = loadedJa.ifEmpty { generated?.cues.orEmpty() }
    val dual = remember(jaCues, enCues) { Subtitles.dual(jaCues, enCues) }

    // Hide-subtitle quiz.
    var quizMode by remember { mutableStateOf<QuizMode?>(null) }
    val quiz = remember(jaCues, quizMode) { quizMode?.let { SubtitleQuiz(jaCues, it, seed = System.currentTimeMillis()) } }
    var quizAt by remember(quiz) { mutableIntStateOf(0) }
    var quizResult by remember(quiz, quizAt) { mutableStateOf<QuizResult?>(null) }
    var revealed by remember(quiz, quizAt) { mutableStateOf(false) }
    var stopAt by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(player) {
        var lastSaved = 0L
        while (true) {
            position = player.currentPosition
            loop?.let { l -> if (position >= l.endMs || position < l.startMs - 500) player.seekTo(l.startMs) }
            stopAt?.let { if (position >= it) { player.pause(); stopAt = null } }
            if (source.onPosition != null && kotlin.math.abs(position - lastSaved) > 5_000) {
                lastSaved = position
                runCatching { source.onPosition.invoke(position) }
            }
            delay(150)
        }
    }

    val cue = Subtitles.cueAt(jaCues, position)
    val current = cue?.let { c -> dual.firstOrNull { it.startMs == c.startMs && it.japanese == c.text } }
    val index = if (jaCues.isEmpty()) -1 else Subtitles.indexAt(jaCues, position)
    fun seekTo(ms: Long) {
        player.seekTo(ms)
        position = ms
    }
    fun seekToCue(i: Int) {
        val target = dual.getOrNull(i) ?: return
        loop = null
        seekTo(target.startMs)
    }

    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { useController = false; this.player = player } },
        update = { it.player = player },
        modifier = if (hasVideo) Modifier.fillMaxWidth().aspectRatio(16f / 9f) else Modifier.fillMaxWidth().height(0.dp),
    )
    if (!hasVideo) JaText(source.title, style = MaterialTheme.typography.titleMedium)
    playerError?.let { Text(stringResource(R.string.media_play_failed, it), color = MaterialTheme.colorScheme.error) }

    val previousLabel = stringResource(R.string.media_previous)
    val nextLabel = stringResource(R.string.media_next)
    val playPauseLabel = stringResource(if (playing) R.string.media_pause else R.string.media_play)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { if (jaCues.isEmpty()) seekTo((position - 10_000).coerceAtLeast(0)) else seekToCue(if (cue != null) index - 1 else index) }, modifier = Modifier.semantics { contentDescription = previousLabel }) { Text("⏮") }
        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.semantics { contentDescription = playPauseLabel }) { Text(if (playing) "⏸" else "▶") }
        IconButton(onClick = { if (jaCues.isEmpty()) seekTo(position + 10_000) else seekToCue(index + 1) }, modifier = Modifier.semantics { contentDescription = nextLabel }) { Text("⏭") }
        FilterChip(
            selected = loop != null,
            onClick = { loop = if (loop != null) null else current ?: dual.getOrNull(index) },
            label = { Text(stringResource(R.string.media_loop)) },
            enabled = dual.isNotEmpty(),
        )
        Text(time(position), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.media_speed), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
        MediaPlayback.SPEEDS.forEach { s ->
            FilterChip(speed == s, { speed = MediaPlayback.clampSpeed(s); player.setPlaybackSpeed(speed.toFloat()) }, { Text("×$s") })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(showJa, { showJa = !showJa }, { Text(stringResource(R.string.media_show_ja)) })
        FilterChip(showEn, { showEn = !showEn }, { Text(stringResource(R.string.media_show_en)) })
        FilterChip(quizMode != null, { quizMode = if (quizMode == null) QuizMode.TYPE else null; stopAt = null }, { Text(stringResource(R.string.quiz_toggle)) }, enabled = jaCues.isNotEmpty())
    }

    // Subtitle generation (on-device Whisper), with progress and cancel.
    if (loadedJa.isEmpty()) {
        val gen = generated
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (gen != null) {
                    Text(stringResource(if (gen.fromCache) R.string.subgen_cached else R.string.subgen_done, gen.cues.size), style = MaterialTheme.typography.bodyMedium)
                    AiBadge(gen.engine)
                } else if (progress != null) {
                    val p = progress!!
                    Text(stringResource(R.string.subgen_running, p.windowsDone, p.windowsTotal), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { genJob?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
                } else {
                    Text(stringResource(R.string.subgen_intro), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = ::generate) { Text(stringResource(R.string.subgen_start)) }
                }
                genError?.let { ErrorState(stringResource(R.string.subgen_failed, it), onRetry = ::generate) }
            }
        }
    }

    if (dual.isEmpty()) {
        Text(stringResource(R.string.media_add_subs), style = MaterialTheme.typography.bodySmall)
        return
    }

    if (quiz != null) {
        QuizPanel(
            quiz, quizAt, quizResult, revealed,
            onMode = { quizMode = it },
            onPlay = { cueToPlay ->
                loop = null
                seekTo(cueToPlay.startMs)
                stopAt = cueToPlay.endMs
                player.play()
            },
            onAnswer = { i, given -> quizResult = quiz.answer(i, given) },
            onReveal = { i -> quiz.reveal(i); revealed = true },
            onNext = { quizAt++ },
        )
    } else {
        var clipStatus by remember { mutableStateOf<String?>(null) }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (current == null) {
                    Text(" ", style = MaterialTheme.typography.titleLarge)
                } else {
                    if (showJa) {
                        JaText(
                            current.japanese,
                            Modifier.clickable(onClickLabel = stringResource(R.string.reader_look_up)) { player.pause(); onLookup(current.japanese) },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (showEn) current.english?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    TextButton(onClick = {
                        val line = current
                        clipStatus = context.getString(R.string.clip_saving)
                        scope.launch { clipStatus = saveClip(context, graph, source, line) }
                    }) { Text(stringResource(R.string.clip_save)) }
                }
                clipStatus?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Text(stringResource(R.string.media_tap_hint), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.media_all_lines), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
    val jumpLabel = stringResource(R.string.media_jump)
    dual.forEachIndexed { i, d ->
        Text(
            buildAnnotatedString { append("${time(d.startMs)}  "); if (quiz == null) append(ja(d.japanese)) else append("…") },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = jumpLabel) { seekToCue(i) }.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium.japanese(),
            color = if (d == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Saves [line] as a LISTENING card and cuts its audio (Media3 Transformer); without audio the review uses TTS. */
private suspend fun saveClip(context: Context, graph: app.tsumugi.api.AppGraph, source: StudioSource, line: DualCue): String = try {
    val draft = graph.clips.saveClip(source.key(), source.title, line.startMs, line.endMs, line.japanese, line.english)
    val cut = try {
        val duration = ClipCutter.cut(context, source.uri, draft.startMs, draft.endMs, draft.audio.path)
        graph.clips.attachAudio(draft.clip.id, draft.audio, duration)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }
    context.getString(if (cut) R.string.clip_saved else R.string.clip_saved_no_audio)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    context.getString(R.string.clip_failed, e.readable())
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuizPanel(
    quiz: SubtitleQuiz,
    at: Int,
    result: QuizResult?,
    revealed: Boolean,
    onMode: (QuizMode) -> Unit,
    onPlay: (Cue) -> Unit,
    onAnswer: (Int, String) -> Unit,
    onReveal: (Int) -> Unit,
    onNext: () -> Unit,
) {
    val indices = quiz.questionIndices
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.quiz_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(quiz.mode == QuizMode.TYPE, { onMode(QuizMode.TYPE) }, { Text(stringResource(R.string.quiz_type)) })
                FilterChip(quiz.mode == QuizMode.PICK, { onMode(QuizMode.PICK) }, { Text(stringResource(R.string.quiz_pick)) })
            }
            val (right, answered) = quiz.score
            if (answered > 0) Text(stringResource(R.string.quiz_score, right, answered), style = MaterialTheme.typography.labelLarge)
            if (indices.isEmpty()) {
                Text(stringResource(R.string.quiz_none))
                return@Column
            }
            val cueIndex = indices.getOrNull(at)
            if (cueIndex == null) {
                Text(stringResource(R.string.quiz_done, right, answered))
                return@Column
            }
            val question = remember(quiz, cueIndex) { quiz.question(cueIndex) }
            Text(stringResource(R.string.quiz_progress, at + 1, indices.size), style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { onPlay(question.cue) }) { Text(stringResource(R.string.quiz_play)) }
            if (result == null && !revealed) {
                if (quiz.mode == QuizMode.PICK) {
                    question.choices.forEach { choice ->
                        OutlinedButton(onClick = { onAnswer(cueIndex, choice) }, modifier = Modifier.fillMaxWidth()) { JaText(choice) }
                    }
                } else {
                    var typed by remember(cueIndex) { mutableStateOf("") }
                    OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.quiz_type_hint)) }, textStyle = MaterialTheme.typography.bodyLarge.japanese())
                    Button(onClick = { onAnswer(cueIndex, typed) }, enabled = typed.isNotBlank()) { Text(stringResource(R.string.action_check)) }
                }
                TextButton(onClick = { onReveal(cueIndex) }) { Text(stringResource(R.string.quiz_reveal)) }
            } else {
                result?.let { r ->
                    Text(
                        stringResource(if (r.correct) R.string.review_correct else R.string.review_not_quite) + " (${(r.score * 100).toInt()}%)",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = if (r.correct) androidx.compose.ui.graphics.Color(0xFF2E7D32) else androidx.compose.ui.graphics.Color(0xFFC62828),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                JaText(question.cue.text, style = MaterialTheme.typography.titleLarge)
                Button(onClick = onNext) { Text(stringResource(R.string.action_next)) }
            }
        }
    }
}

/** Subtitle files are usually UTF-8; older Japanese ones are often Shift_JIS. */
private suspend fun readText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("can't open the file")
    try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("Shift_JIS"))
    }
}
