package app.tsumugi.android.features.practice

import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.platform.MediaPcmSource
import app.tsumugi.android.platform.PodcastDownloads
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.readable
import app.tsumugi.media.DownloadState
import app.tsumugi.media.Podcast
import app.tsumugi.media.PodcastEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Podcasts (G-04, D-113): subscribe to RSS feeds with audio enclosures. Feeds are fetched only on request. */
@Composable
fun PodcastsScreen(onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<Podcast>?>(null) }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    suspend fun reload() {
        runCatching { graph.podcasts.podcasts() }.onSuccess { list = it }.onFailure { error = it.readable(); retry = { scope.launch { reload() } } }
    }
    LaunchedEffect(Unit) { reload() }
    fun subscribe() {
        val feed = url.trim()
        busy = true
        error = null
        scope.launch {
            try {
                val p = graph.podcasts.subscribe(feed)
                url = ""
                reload()
                onOpen(p.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.getString(R.string.podcast_subscribe_failed, e.readable())
                retry = { url = feed; subscribe() }
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.podcast_intro), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.podcast_feed_url)) }, singleLine = true)
            Button(onClick = ::subscribe, enabled = url.isNotBlank() && !busy) { Text(stringResource(R.string.action_add)) }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(it, onRetry = { retry?.invoke() }) }
        val l = list
        when {
            l == null -> if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            l.isEmpty() -> Notice(stringResource(R.string.podcast_none))
            else -> l.forEach { p ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(p.id) },
                    headlineContent = { JaText(p.title, maxLines = 2) },
                    supportingContent = { p.author?.let { Text(it) } },
                    trailingContent = { TextButton(onClick = { scope.launch { graph.podcasts.unsubscribe(p.id); reload() } }) { Text(stringResource(R.string.podcast_unsubscribe)) } },
                )
            }
        }
    }
}

private fun duration(ms: Long?): String? = ms?.let { val m = it / 60_000; if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min" }

/** One podcast: its episodes with background downloads (WorkManager, cancellable) and playback once downloaded. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PodcastScreen(id: String, onPlay: (String) -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var podcast by remember { mutableStateOf<Podcast?>(null) }
    var episodes by remember { mutableStateOf<List<PodcastEpisode>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun reload() {
        podcast = graph.podcasts.podcast(id)
        episodes = graph.podcasts.episodes(id)
    }
    fun refresh() {
        refreshing = true
        error = null
        scope.launch {
            try {
                graph.podcasts.refresh(id)
                reload()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = context.getString(R.string.podcast_refresh_failed, e.readable())
            } finally {
                refreshing = false
            }
        }
    }
    LaunchedEffect(id) {
        runCatching { reload() }.onFailure { error = it.readable() }
        // Downloads run in WorkManager; follow their progress while any is active.
        while (true) {
            delay(1_000)
            if (episodes.orEmpty().any { it.downloadState == DownloadState.QUEUED || it.downloadState == DownloadState.DOWNLOADING }) runCatching { reload() }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        podcast?.let { p ->
            JaText(p.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            p.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
        OutlinedButton(onClick = ::refresh, enabled = !refreshing) { Text(stringResource(R.string.podcast_refresh)) }
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorState(it, onRetry = ::refresh) }
        val list = episodes
        if (list != null && list.isEmpty()) Notice(stringResource(R.string.podcast_no_episodes))
        list?.forEach { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JaText(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 3)
                    Text(listOfNotNull(e.published?.take(16), duration(e.durationMs)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    when (e.downloadState) {
                        DownloadState.NONE -> OutlinedButton(onClick = { scope.launch { PodcastDownloads.start(context, e.id); reload() } }) { Text(stringResource(R.string.podcast_download)) }
                        DownloadState.QUEUED, DownloadState.DOWNLOADING -> {
                            val f = e.downloadFraction
                            if (f != null) LinearProgressIndicator(progress = { f.toFloat() }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.podcast_downloading, e.downloadedBytes / 1_048_576), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { scope.launch { PodcastDownloads.cancel(context, e.id); reload() } }) { Text(stringResource(R.string.action_cancel)) }
                        }
                        DownloadState.DONE -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onPlay(e.id) }) { Text(stringResource(R.string.media_play)) }
                            TextButton(onClick = { scope.launch { graph.podcasts.deleteDownload(e.id); reload() } }) { Text(stringResource(R.string.podcast_delete_download)) }
                        }
                        DownloadState.FAILED -> {
                            ErrorState(stringResource(R.string.podcast_download_failed, e.downloadError.orEmpty()), onRetry = { scope.launch { PodcastDownloads.start(context, e.id); reload() } })
                        }
                    }
                }
            }
        }
    }
}

/** A downloaded episode in the media studio, with its transcript generated on device (cached by content key). */
@Composable
fun EpisodeScreen(id: String, onLookup: (String) -> Unit) {
    val graph = rememberGraph()
    val context = LocalContext.current
    var episode by remember { mutableStateOf<PodcastEpisode?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val e = graph.podcasts.episode(id)
        missing = e?.localPath == null || !File(e.localPath!!).exists()
        episode = e
    }
    val e = episode
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            e == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            missing -> Notice(stringResource(R.string.podcast_not_downloaded))
            else -> {
                JaText(e.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                val uri = remember(e.id) { Uri.fromFile(File(e.localPath!!)) }
                val source = remember(e.id) {
                    StudioSource(
                        uri, e.title,
                        key = { e.mediaHash ?: app.tsumugi.android.platform.mediaHash(context, uri) },
                        generate = { onProgress -> graph.podcasts.transcript(e.id, graph.subtitles, MediaPcmSource(context, uri), onProgress) },
                        startPositionMs = e.positionMs,
                        onPosition = { ms -> graph.podcasts.setPosition(e.id, ms) },
                        origin = app.tsumugi.immersion.ImmersionOrigin.PODCAST,
                    )
                }
                MediaStudio(source, emptyList(), emptyList(), onLookup)
            }
        }
    }
}
