package app.tsumugi.android.features.lyrics

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.tsumugi.android.R
import app.tsumugi.android.app.displayName
import app.tsumugi.android.features.decks.readTextFile
import app.tsumugi.android.features.immersion.ImmersionTracker
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.MediaPcmSource
import app.tsumugi.android.platform.mediaHash
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.Exports
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.immersion.ImmersionMode
import app.tsumugi.immersion.ImmersionOrigin
import app.tsumugi.lyrics.ClozeBlank
import app.tsumugi.lyrics.ClozeSession
import app.tsumugi.lyrics.ClozeSpan
import app.tsumugi.lyrics.ClozeState
import app.tsumugi.lyrics.Karaoke
import app.tsumugi.lyrics.LyricLine
import app.tsumugi.lyrics.LyricLineStudy
import app.tsumugi.lyrics.LyricsSong
import app.tsumugi.lyrics.LyricsSongSummary
import app.tsumugi.lyrics.LyricsTiming
import app.tsumugi.media.SubtitleProgress
import app.tsumugi.srs.Verdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Songs (BRIEF_V2 §6.3, D-163): the learner's own audio files with .lrc or plain lyrics. No streaming or downloading;
 * the empty state says so. Songs stay on this device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongsScreen(onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<LyricsSongSummary>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var audio by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf("") }
    var artist by remember { mutableStateOf("") }
    var lyrics by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<LyricsSongSummary?>(null) }
    LaunchedEffect(version) {
        runCatching { graph.lyrics.songs() }.onSuccess { songs = it; error = null }.onFailure { error = it.readable() }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // The song keeps pointing at the learner's file: keep read access across restarts.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        audio = uri
        title = displayName(context, uri).substringBeforeLast('.')
        artist = ""
        lyrics = ""
        importError = null
    }
    val lyricsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { runCatching { readTextFile(context, uri) }.onSuccess { lyrics = it }.onFailure { importError = it.readable() } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { audioPicker.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.lyrics_import)) }
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { version++ }) }
        val list = songs
        when {
            list == null && error == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list != null && list.isEmpty() -> Notice(stringResource(R.string.lyrics_empty))
            list != null -> list.forEach { s ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(s.id) },
                    headlineContent = { JaText(s.title, style = MaterialTheme.typography.titleMedium) },
                    supportingContent = { Text(listOfNotNull(s.artist, timingLabel(s.timing)).joinToString(" · ")) },
                    trailingContent = { TextButton(onClick = { confirmDelete = s }) { Text(stringResource(R.string.action_delete)) } },
                )
            }
        }
    }

    audio?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!importing) audio = null },
            title = { Text(stringResource(R.string.lyrics_import)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayName(context, uri), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lyrics_title)) }, singleLine = true)
                    OutlinedTextField(artist, { artist = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lyrics_artist)) }, singleLine = true)
                    OutlinedButton(onClick = { lyricsPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.lyrics_pick_file)) }
                    OutlinedTextField(
                        lyrics, { lyrics = it }, Modifier.fillMaxWidth(), minLines = 5, maxLines = 12,
                        label = { Text(stringResource(R.string.lyrics_text)) }, textStyle = MaterialTheme.typography.bodyMedium.japanese(),
                    )
                    Text(stringResource(R.string.lyrics_import_hint), style = MaterialTheme.typography.bodySmall)
                    if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    importError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    importing = true
                    importError = null
                    scope.launch {
                        runCatching {
                            val hash = runCatching { mediaHash(context, uri) }.getOrNull()
                            graph.lyrics.importSong(title, artist, uri.toString(), lyrics, hash)
                        }.onSuccess { song -> audio = null; version++; onOpen(song.id) }
                            .onFailure { importError = context.getString(R.string.lyrics_import_failed, it.readable()) }
                        importing = false
                    }
                }, enabled = lyrics.isNotBlank() && !importing) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = { TextButton(onClick = { audio = null }, enabled = !importing) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            text = { Text(stringResource(R.string.lyrics_delete_confirm, s.title)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; scope.launch { runCatching { graph.lyrics.delete(s.id) }; version++ } }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun timingLabel(t: LyricsTiming): String = stringResource(
    when (t) {
        LyricsTiming.LRC_WORDS -> R.string.lyrics_timing_words
        LyricsTiming.LRC_LINES -> R.string.lyrics_timing_lines
        LyricsTiming.ALIGNED -> R.string.lyrics_timing_aligned
        LyricsTiming.NONE -> R.string.lyrics_timing_none
    },
)

private fun time(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/**
 * The karaoke view (§6.3): the current line with the sung words highlighted (tap a word for the dictionary), all
 * lines, per-line translation (the learner's own, or AI with its badge) and grammar notes, cloze mode, and alignment
 * of plain lyrics with on-device Whisper (progress and cancel).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongScreen(id: String, onLookup: (String) -> Unit, onOpenGrammar: (String) -> Unit, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var song by remember(id) { mutableStateOf<LyricsSong?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, version) {
        runCatching { graph.lyrics.song(id) }.onSuccess { song = it; loadError = null }.onFailure { loadError = it.readable() }
        loaded = true
    }
    val s = song
    if (s == null) {
        Column(Modifier.padding(16.dp)) {
            when {
                loadError != null -> ErrorState(stringResource(R.string.error_loading, loadError!!), onRetry = { version++ })
                loaded -> Text(stringResource(R.string.lyrics_gone))
                else -> LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        return
    }
    val uri = remember(s.audioLocator) { Uri.parse(s.audioLocator) }
    val player = remember(s.audioLocator) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() } }
    var playing by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(error: PlaybackException) { playerError = error.message ?: error.errorCodeName }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    ImmersionTracker(ImmersionOrigin.MEDIA, ImmersionMode.ACTIVE, "song:$id", s.title, active = playing)

    var cloze by remember { mutableStateOf(false) }
    var session by remember(s, cloze) { mutableStateOf<ClozeSession?>(null) }
    var clozeTick by remember { mutableIntStateOf(0) }
    var dueBlank by remember { mutableStateOf<ClozeBlank?>(null) }
    LaunchedEffect(s, cloze) { session = if (cloze) runCatching { graph.lyrics.clozeSession(id) }.getOrNull() else null }
    LaunchedEffect(player, session) {
        while (true) {
            position = player.currentPosition
            session?.let { cs ->
                val due = cs.update(position)
                if (due.isNotEmpty() && dueBlank == null) {
                    // AxTongue's mechanic: pause when a hidden word has been sung, and ask for it.
                    player.pause()
                    dueBlank = due.first()
                    clozeTick++
                }
            }
            delay(100)
        }
    }

    val lines = s.lines
    val karaoke = if (s.timed) Karaoke.at(lines, position) else null
    var selected by remember(id) { mutableStateOf<Int?>(null) }
    val focus = selected ?: karaoke?.lineIndex?.takeIf { it >= 0 } ?: 0
    val studies = remember(s) { mutableStateMapOf<Int, LyricLineStudy?>() }
    LaunchedEffect(s, focus) { if (focus !in studies) studies[focus] = runCatching { graph.lyrics.lineStudy(id, focus) }.getOrNull() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        JaText(s.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        s.artist?.let { JaText(it, style = MaterialTheme.typography.bodyMedium) }
        playerError?.let { ErrorState(stringResource(R.string.lyrics_audio_failed, it), onRetry = { playerError = null; player.prepare() }) }
        val playLabel = stringResource(if (playing) R.string.media_pause else R.string.media_play)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.semantics { contentDescription = playLabel }) { Text(if (playing) "⏸" else "▶") }
            Text(time(position), style = MaterialTheme.typography.labelMedium)
            FilterChip(!cloze, { cloze = false; dueBlank = null }, { Text(stringResource(R.string.lyrics_mode_karaoke)) })
            FilterChip(cloze, { cloze = true }, { Text(stringResource(R.string.lyrics_mode_cloze)) })
        }

        if (!s.timed || s.timing == LyricsTiming.ALIGNED) AlignCard(s, uri, onAligned = { version++ }, onOpenAiSettings = onOpenAiSettings)

        if (cloze) ClozeCard(s, session, dueBlank, clozeTick, onResume = { dueBlank = null; player.play() }, onAuto = {
            scope.launch { runCatching { graph.lyrics.autoCloze(id) }; version++ }
        })

        // The current (or selected) line, word by word.
        val line = lines.getOrNull(focus)
        if (line != null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val study = studies[focus]
                    val masked = session?.masked(focus)
                    if (masked != null && masked != line.text) {
                        JaText(masked, style = MaterialTheme.typography.headlineSmall)
                    } else if (study != null && study.tokens.isNotEmpty()) {
                        val lookLabel = stringResource(R.string.reader_look_up)
                        FlowRow {
                            study.tokens.forEach { t ->
                                val sung = line.words.any { w -> w.start < t.end && w.end > t.start && w.startMs <= position } && karaoke?.lineIndex == focus
                                Text(
                                    t.surface,
                                    Modifier.clickable(enabled = t.isWord, onClickLabel = lookLabel) { player.pause(); onLookup(t.dictionaryForm ?: t.surface) }.padding(horizontal = 1.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.headlineSmall.japanese(),
                                    color = if (sung) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = if (sung) FontWeight.Bold else null,
                                )
                            }
                        }
                    } else {
                        Text(sungText(line, if (karaoke?.lineIndex == focus) position else -1), style = MaterialTheme.typography.headlineSmall.japanese())
                    }
                    LineStudyPanel(id, focus, line, study, onChanged = { version++ }, onOpenGrammar = onOpenGrammar, onOpenAiSettings = onOpenAiSettings)
                }
            }
        }

        Text(stringResource(R.string.lyrics_all_lines), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        val jumpLabel = stringResource(R.string.media_jump)
        lines.forEachIndexed { i, l ->
            val current = karaoke?.lineIndex == i
            Text(
                buildAnnotatedString {
                    l.startMs?.let { append("${time(it)}  ") }
                    append(ja(session?.masked(i) ?: l.text))
                },
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = jumpLabel) {
                    selected = i
                    l.startMs?.let { player.seekTo(it) }
                }.padding(vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge.japanese(),
                color = if (current) MaterialTheme.colorScheme.primary else if (i == focus) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (current) FontWeight.Bold else null,
            )
        }
        if (selected != null) TextButton(onClick = { selected = null }) { Text(stringResource(R.string.lyrics_follow)) }
        HorizontalDivider()
        OutlinedButton(onClick = {
            scope.launch {
                runCatching {
                    val text = graph.lyrics.exportLrc(id) ?: error("no song")
                    val file = Exports.file(context, "${s.title.take(40).replace(Regex("[\\\\/:*?\"<>|]"), "_")}.lrc")
                    withContext(Dispatchers.IO) { file.writeText(text) }
                    Exports.share(context, file, "text/plain", context.getString(R.string.lyrics_export))
                }
            }
        }) { Text(stringResource(R.string.lyrics_export)) }
    }
}

/** The line with the words already sung in bold (used when the tokenizer isn't installed). */
@Composable
private fun sungText(line: LyricLine, position: Long) = buildAnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    if (position < 0 || line.words.isEmpty()) {
        append(ja(line.text))
        return@buildAnnotatedString
    }
    val sungEnd = line.words.filter { it.startMs <= position }.maxOfOrNull { it.end } ?: 0
    withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(ja(line.text.substring(0, sungEnd.coerceIn(0, line.text.length)))) }
    append(ja(line.text.substring(sungEnd.coerceIn(0, line.text.length))))
}

/** "Align with Whisper" for plain lyrics (or re-align), with progress and cancel (rule 15). */
@Composable
private fun AlignCard(song: LyricsSong, uri: Uri, onAligned: () -> Unit, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<SubtitleProgress?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun align() {
        error = null
        progress = SubtitleProgress(0, 0)
        job = scope.launch {
            try {
                val hash = song.mediaHash ?: mediaHash(context, uri)
                graph.lyrics.align(song.id, hash, MediaPcmSource(context, uri)) { progress = it }
                onAligned()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.readable()
            } finally {
                progress = null
                job = null
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(if (song.timed) R.string.lyrics_aligned_note else R.string.lyrics_not_timed), style = MaterialTheme.typography.bodySmall)
            val p = progress
            if (p != null) {
                Text(stringResource(R.string.lyrics_aligning, p.windowsDone, p.windowsTotal), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                LinearProgressIndicator(progress = { p.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            } else {
                OutlinedButton(onClick = ::align) { Text(stringResource(if (song.timed) R.string.lyrics_realign else R.string.lyrics_align)) }
            }
            error?.let {
                ErrorState(stringResource(R.string.lyrics_align_failed, it), onRetry = ::align)
                TextButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.title_ai)) }
            }
        }
    }
}

/** Cloze mode: the blank that was just sung, typed in (kana, kanji or romaji), with the running score. */
@Composable
private fun ClozeCard(song: LyricsSong, session: ClozeSession?, due: ClozeBlank?, tick: Int, onResume: () -> Unit, onAuto: () -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val cs = session
            if (cs == null) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                return@Column
            }
            if (cs.blanks.isEmpty()) {
                Text(stringResource(R.string.lyrics_cloze_none), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onAuto) { Text(stringResource(R.string.lyrics_cloze_auto)) }
                return@Column
            }
            val (right, answered) = cs.score
            Text(stringResource(R.string.lyrics_cloze_score, right, answered, cs.blanks.size), style = MaterialTheme.typography.labelLarge)
            if (!song.timed) Text(stringResource(R.string.lyrics_cloze_untimed), style = MaterialTheme.typography.bodySmall)
            if (due == null) {
                Text(stringResource(R.string.lyrics_cloze_listen), style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            var typed by remember(due.id, tick) { mutableStateOf("") }
            var verdict by remember(due.id, tick) { mutableStateOf<String?>(null) }
            JaText(cs.masked(due.lineIndex), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lyrics_cloze_type)) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.japanese())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val a = cs.answer(due.id, typed)
                    verdict = context.getString(
                        when (a.verdict) {
                            Verdict.CORRECT -> R.string.lyrics_cloze_right
                            Verdict.CLOSE -> R.string.lyrics_cloze_close
                            else -> R.string.lyrics_cloze_wrong
                        },
                        a.expected,
                    )
                }, enabled = typed.isNotBlank() && verdict == null) { Text(stringResource(R.string.action_check)) }
                TextButton(onClick = { cs.reveal(due.id); verdict = context.getString(R.string.lyrics_cloze_revealed, due.answer) }, enabled = verdict == null) { Text(stringResource(R.string.quiz_reveal)) }
            }
            verdict?.let {
                Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (cs.blanks.firstOrNull { b -> b.id == due.id }?.state == ClozeState.CORRECT) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurface)
                Button(onClick = onResume) { Text(stringResource(R.string.lyrics_cloze_continue)) }
            }
        }
    }
}

/** Translation (own or AI-labeled), grammar notes and cloze picks for one line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineStudyPanel(
    songId: String,
    index: Int,
    line: LyricLine,
    study: LyricLineStudy?,
    onChanged: () -> Unit,
    onOpenGrammar: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember(index) { mutableStateOf(false) }
    var draft by remember(index) { mutableStateOf("") }
    var translating by remember(index) { mutableStateOf(false) }
    var note by remember(index) { mutableStateOf<String?>(null) }
    var needsSetup by remember(index) { mutableStateOf(false) }
    line.translation?.let { t ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(t, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
            if (line.aiTranslated) AiBadge(line.translationEngine)
        }
    }
    if (editing) {
        OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.lyrics_my_translation)) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch { runCatching { graph.lyrics.setTranslation(songId, index, draft) }; editing = false; onChanged() }
            }) { Text(stringResource(R.string.action_save)) }
            TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
        }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { draft = if (line.aiTranslated) "" else line.translation.orEmpty(); editing = true }) { Text(stringResource(R.string.lyrics_write_translation)) }
            if (line.translation == null || line.aiTranslated) {
                TextButton(onClick = {
                    translating = true
                    note = null
                    scope.launch {
                        runCatching { graph.lyrics.translateLine(songId, index) }
                            .onSuccess { o ->
                                needsSetup = o.needsSetup
                                note = o.unavailable ?: if (o.needsSetup) context.getString(R.string.lyrics_ai_setup) else null
                                onChanged()
                            }
                            .onFailure { note = it.readable() }
                        translating = false
                    }
                }, enabled = !translating) { Text(stringResource(R.string.lyrics_ai_translate)) }
            }
        }
    }
    if (translating) LinearProgressIndicator(Modifier.fillMaxWidth())
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (needsSetup) TextButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.title_ai)) }
    study?.grammar?.takeIf { it.isNotEmpty() }?.let { notes ->
        Text(stringResource(R.string.lyrics_grammar), style = MaterialTheme.typography.labelLarge)
        notes.forEach { g ->
            Row(Modifier.fillMaxWidth().clickable { onOpenGrammar(g.pointId) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    JaText(g.title, style = MaterialTheme.typography.bodyMedium)
                    Text("N${g.jlpt} · ${g.meaning}", style = MaterialTheme.typography.bodySmall)
                }
                if (g.aiGenerated) AiBadge()
            }
        }
    }
    // Cloze picks: tap a word to hide it until it's sung.
    val words = study?.tokens?.filter { it.isWord }.orEmpty()
    if (words.isNotEmpty()) {
        Text(stringResource(R.string.lyrics_cloze_pick), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            words.forEach { t ->
                val hidden = line.cloze.any { it.start == t.start && it.end == t.end }
                FilterChip(hidden, {
                    val spans = if (hidden) line.cloze.filterNot { it.start == t.start && it.end == t.end }
                    else line.cloze + ClozeSpan(t.start, t.end, t.reading)
                    scope.launch { runCatching { graph.lyrics.setCloze(songId, index, spans) }; onChanged() }
                }, { Text(ja(t.surface)) })
            }
        }
    }
}
