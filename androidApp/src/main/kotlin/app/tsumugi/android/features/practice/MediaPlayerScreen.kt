package app.tsumugi.android.features.practice

import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import android.content.Context
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.tsumugi.android.ui.japanese
import app.tsumugi.media.Cue
import app.tsumugi.media.DualCue
import app.tsumugi.media.Subtitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Media player for the learner's own files (BRIEF §5.9): a local video or audio file plus Japanese subtitles
 * (.srt/.vtt) and optional English ones, picked with the system file picker. Dual subtitles, tap a line to look it
 * up, previous/next line, and an A-B loop on the current line. Uses the platform VideoView (no extra dependency).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaPlayerScreen(onLookup: (String) -> Unit) {
    val context = LocalContext.current
    var media by rememberSaveable { mutableStateOf<String?>(null) }
    var jaSubs by rememberSaveable { mutableStateOf<String?>(null) }
    var enSubs by rememberSaveable { mutableStateOf<String?>(null) }
    var jaCues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var dual by remember { mutableStateOf<List<DualCue>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { media = it.toString() } }
    val jaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { jaSubs = it.toString() } }
    val enPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { enSubs = it.toString() } }

    LaunchedEffect(jaSubs, enSubs) {
        error = null
        val ja = jaSubs?.let { runCatching { Subtitles.parse(readText(context, Uri.parse(it))) }.onFailure { e -> error = context.getString(R.string.media_ja_failed, e.message.orEmpty()) }.getOrNull() }.orEmpty()
        val en = enSubs?.let { runCatching { Subtitles.parse(readText(context, Uri.parse(it))) }.onFailure { e -> error = context.getString(R.string.media_en_failed, e.message.orEmpty()) }.getOrNull() }.orEmpty()
        if (jaSubs != null && ja.isEmpty() && error == null) error = context.getString(R.string.media_no_cues)
        jaCues = ja
        dual = Subtitles.dual(ja, en)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { mediaPicker.launch(arrayOf("video/*", "audio/*")) }) { Text(stringResource(if (media == null) R.string.media_choose else R.string.media_change)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { jaPicker.launch(arrayOf("*/*")) }) { Text(stringResource(if (jaSubs == null) R.string.media_ja_subs else R.string.media_ja_subs_ok)) }
            OutlinedButton(onClick = { enPicker.launch(arrayOf("*/*")) }) { Text(stringResource(if (enSubs == null) R.string.media_en_subs else R.string.media_en_subs_ok)) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val uri = media
        if (uri == null) {
            Notice(
                stringResource(R.string.media_intro),
            )
        } else {
            Player(Uri.parse(uri), jaCues, dual, onLookup)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Player(uri: Uri, jaCues: List<Cue>, dual: List<DualCue>, onLookup: (String) -> Unit) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf<DualCue?>(null) }
    var showJa by remember { mutableStateOf(true) }
    var showEn by remember { mutableStateOf(true) }

    AndroidView(
        factory = { ctx -> VideoView(ctx).also { view = it } },
        update = { v -> if (v.tag != uri) { v.tag = uri; v.setVideoURI(uri); v.setOnCompletionListener { playing = false } } },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
    DisposableEffect(Unit) { onDispose { view?.stopPlayback() } }

    // Poll the position for subtitles and the A-B loop.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        while (true) {
            position = v.currentPosition.toLong()
            loop?.let { l -> if (position >= l.endMs || position < l.startMs - 500) v.seekTo(l.startMs.toInt()) }
            delay(150)
        }
    }

    val cue = Subtitles.cueAt(jaCues, position)
    val current = cue?.let { c -> dual.firstOrNull { it.startMs == c.startMs && it.japanese == c.text } }
    val index = if (jaCues.isEmpty()) -1 else Subtitles.indexAt(jaCues, position)
    fun seekToCue(i: Int) {
        val target = dual.getOrNull(i) ?: return
        loop = null
        view?.seekTo(target.startMs.toInt())
        position = target.startMs
    }

    val previousLabel = stringResource(R.string.media_previous)
    val nextLabel = stringResource(R.string.media_next)
    val playPauseLabel = stringResource(if (playing) R.string.media_pause else R.string.media_play)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = { seekToCue(if (cue != null) index - 1 else index) }, modifier = Modifier.semantics { contentDescription = previousLabel }) { Text("⏮") }
        IconButton(
            onClick = {
                val v = view ?: return@IconButton
                if (v.isPlaying) v.pause() else v.start()
                playing = v.isPlaying
            },
            modifier = Modifier.semantics { contentDescription = playPauseLabel },
        ) { Text(if (playing) "⏸" else "▶") }
        IconButton(onClick = { seekToCue(index + 1) }, modifier = Modifier.semantics { contentDescription = nextLabel }) { Text("⏭") }
        FilterChip(
            selected = loop != null,
            onClick = { loop = if (loop != null) null else current ?: dual.getOrNull(index) },
            label = { Text(stringResource(R.string.media_loop)) },
            enabled = dual.isNotEmpty(),
        )
        Text(time(position), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(showJa, { showJa = !showJa }, { Text(stringResource(R.string.media_show_ja)) })
        FilterChip(showEn, { showEn = !showEn }, { Text(stringResource(R.string.media_show_en)) })
    }
    if (dual.isEmpty()) {
        Text(stringResource(R.string.media_add_subs), style = MaterialTheme.typography.bodySmall)
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (current == null) {
                Text(" ", style = MaterialTheme.typography.titleLarge)
            } else {
                if (showJa) {
                    JaText(
                        current.japanese,
                        Modifier.clickable(onClickLabel = stringResource(R.string.reader_look_up)) { view?.pause(); playing = false; onLookup(current.japanese) },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (showEn) current.english?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    Text(stringResource(R.string.media_tap_hint), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.media_all_lines), style = MaterialTheme.typography.titleSmall)
    val jumpLabel = stringResource(R.string.media_jump)
    dual.forEachIndexed { i, d ->
        Text(
            buildAnnotatedString { append("${time(d.startMs)}  "); append(ja(d.japanese)) },
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = jumpLabel) { seekToCue(i) }.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium.japanese(),
            color = if (d == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun time(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
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
