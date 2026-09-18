package app.tsumugi.android.features.practice

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
        val ja = jaSubs?.let { runCatching { Subtitles.parse(readText(context, Uri.parse(it))) }.onFailure { e -> error = "Couldn't read the Japanese subtitles: ${e.message}" }.getOrNull() }.orEmpty()
        val en = enSubs?.let { runCatching { Subtitles.parse(readText(context, Uri.parse(it))) }.onFailure { e -> error = "Couldn't read the English subtitles: ${e.message}" }.getOrNull() }.orEmpty()
        if (jaSubs != null && ja.isEmpty() && error == null) error = "No subtitle cues found in that file (expected .srt or .vtt)."
        jaCues = ja
        dual = Subtitles.dual(ja, en)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { mediaPicker.launch(arrayOf("video/*", "audio/*")) }) { Text(if (media == null) "Choose video/audio" else "Change media") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { jaPicker.launch(arrayOf("*/*")) }) { Text(if (jaSubs == null) "Japanese subtitles" else "JP subs ✓") }
            OutlinedButton(onClick = { enPicker.launch(arrayOf("*/*")) }) { Text(if (enSubs == null) "English (optional)" else "EN subs ✓") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val uri = media
        if (uri == null) {
            Notice(
                "Play your own video or audio files with Japanese subtitles (.srt or .vtt) and, optionally, English ones. " +
                    "Everything stays on this device. Tsumugi doesn't download from streaming services: use files you already have. " +
                    "Generating subtitles with on-device speech recognition isn't on Android yet.",
            )
        } else {
            Player(Uri.parse(uri), jaCues, dual, onLookup)
        }
    }
}

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

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { seekToCue(if (cue != null) index - 1 else index) }, modifier = Modifier.semantics { contentDescription = "Previous line" }) { Text("⏮") }
        IconButton(
            onClick = {
                val v = view ?: return@IconButton
                if (v.isPlaying) v.pause() else v.start()
                playing = v.isPlaying
            },
            modifier = Modifier.semantics { contentDescription = if (playing) "Pause" else "Play" },
        ) { Text(if (playing) "⏸" else "▶") }
        IconButton(onClick = { seekToCue(index + 1) }, modifier = Modifier.semantics { contentDescription = "Next line" }) { Text("⏭") }
        FilterChip(
            selected = loop != null,
            onClick = { loop = if (loop != null) null else current ?: dual.getOrNull(index) },
            label = { Text("A-B loop") },
            enabled = dual.isNotEmpty(),
        )
        Text(time(position), style = MaterialTheme.typography.labelMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(showJa, { showJa = !showJa }, { Text("JP subtitles") })
        FilterChip(showEn, { showEn = !showEn }, { Text("EN subtitles") })
    }
    if (dual.isEmpty()) {
        Text("Add a Japanese .srt or .vtt file to see subtitles.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (current == null) {
                Text(" ", style = MaterialTheme.typography.titleLarge)
            } else {
                if (showJa) {
                    Text(
                        current.japanese,
                        Modifier.clickable(onClickLabel = "Look up") { view?.pause(); playing = false; onLookup(current.japanese) },
                        style = MaterialTheme.typography.titleLarge.japanese(),
                    )
                }
                if (showEn) current.english?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    Text("Tap the Japanese line to look it up.", style = MaterialTheme.typography.bodySmall)
    Text("All lines", style = MaterialTheme.typography.titleSmall)
    dual.forEachIndexed { i, d ->
        Text(
            "${time(d.startMs)}  ${d.japanese}",
            Modifier.fillMaxWidth().clickable(onClickLabel = "Jump to this line") { seekToCue(i) }.padding(vertical = 6.dp),
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
