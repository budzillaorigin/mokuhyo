package app.tsumugi.android.features.practice

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.decks.DifficultyBadge
import app.tsumugi.android.features.decks.coverageSummary
import app.tsumugi.android.platform.BankClips
import app.tsumugi.android.platform.ClipCutter
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.readable
import app.tsumugi.api.AppGraph
import app.tsumugi.coverage.CoverageService
import app.tsumugi.coverage.DocumentCoverage
import app.tsumugi.coverage.OneTargetSentence
import app.tsumugi.media.Cue
import app.tsumugi.media.CueSource
import app.tsumugi.media.IndexProgress
import app.tsumugi.media.IndexedMedia
import app.tsumugi.media.MediaKind
import app.tsumugi.media.MineKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Video or audio, from the MIME type the provider reports (or the file extension for file:// URIs). */
fun mediaKindOf(context: Context, uri: Uri): MediaKind {
    val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase())
    return if (type?.startsWith("video") == true) MediaKind.VIDEO else MediaKind.AUDIO
}

/**
 * Indexes the subtitles of the playing media into the sentence bank (BRIEF_V2 §6.2, D-160) after they're loaded or
 * generated, so the dictionary finds lines from the learner's own media. Skipped when the same cue count is already
 * indexed; shows progress while it runs (rule 15).
 */
@Composable
fun BankIndexer(source: StudioSource, cues: List<Cue>, fromFile: Boolean, mediaKey: String?) {
    val graph = rememberGraph()
    val context = LocalContext.current
    var progress by remember(source.uri) { mutableStateOf<IndexProgress?>(null) }
    var error by remember(source.uri) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(mediaKey, cues, attempt) {
        val key = mediaKey ?: return@LaunchedEffect
        if (cues.isEmpty()) return@LaunchedEffect
        try {
            val existing = graph.sentenceBank.media(key)
            if (existing != null && existing.cueCount == cues.size && existing.locator == source.uri.toString()) return@LaunchedEffect
            error = null
            graph.sentenceBank.index(
                key, source.title, mediaKindOf(context, source.uri), source.uri.toString(), cues,
                if (fromFile) CueSource.FILE else CueSource.GENERATED,
            ) { p -> progress = p }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.readable()
        } finally {
            progress = null
        }
    }
    progress?.let { p ->
        Text(stringResource(R.string.bank_indexing, p.done, p.total), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
    }
    error?.let { ErrorState(stringResource(R.string.bank_index_failed, it), onRetry = { attempt++ }) }
}

/** Subtitle cues with exactly one unknown word (§6.11 1T), by cue index, computed once per subtitle text. */
class OneTargetState {
    var byCue by mutableStateOf<Map<Int, OneTargetSentence>>(emptyMap())
    var progress by mutableStateOf<Double?>(null)
    var error by mutableStateOf<String?>(null)
    var attempt by mutableIntStateOf(0)
}

@Composable
fun rememberOneTargetCues(srt: String?): OneTargetState {
    val graph = rememberGraph()
    val state = remember(srt) { OneTargetState() }
    LaunchedEffect(srt, state.attempt) {
        if (srt == null) return@LaunchedEffect
        state.progress = 0.0
        state.error = null
        try {
            val found = graph.coverage.oneTargetCues(srt, limit = 200) { state.progress = it }
            state.byCue = found.mapNotNull { s -> s.cueIndex?.let { it to s } }.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = e.readable()
        } finally {
            state.progress = null
        }
    }
    return state
}

@Composable
fun OneTargetStatus(state: OneTargetState) {
    state.progress?.let { p ->
        Text(stringResource(R.string.onet_finding, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { p.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
    state.error?.let { ErrorState(stringResource(R.string.onet_failed, it), onRetry = { state.attempt++ }) }
    if (state.progress == null && state.error == null && state.byCue.isNotEmpty()) {
        Text(stringResource(R.string.onet_count, state.byCue.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

/** Where the 1T target sits in the cue text (a cue may hold more than one sentence). */
fun targetInCue(cueText: String, s: OneTargetSentence): IntRange? {
    val at = cueText.indexOf(s.text).takeIf { it >= 0 } ?: return null
    val start = at + s.targetStart
    val end = at + s.targetEnd
    return if (start in 0 until end && end <= cueText.length) start until end else null
}

/**
 * "Mine this line" (§6.2, D-161): a SENTENCE card (or a VOCAB card for a 1T target) with the line's audio cut from
 * the file (Media3 Transformer, the Phase 10 clip path) and, for video, a frame (MediaMetadataRetriever). The card
 * exists even when a cut fails; the review then speaks the line.
 */
suspend fun mineLine(
    context: Context,
    graph: AppGraph,
    source: StudioSource,
    mediaKey: String,
    line: app.tsumugi.media.DualCue,
    target: OneTargetSentence?,
): String = try {
    val kind = mediaKindOf(context, source.uri)
    val span = target?.let { targetInCue(line.japanese, it) }
    val summary = target?.let { t -> runCatching { graph.dictionary()?.summaries(listOf(t.entryId))?.firstOrNull() }.getOrNull() }
    val draft = graph.sentenceMiner.mineLine(
        kind = if (target != null && summary != null) MineKind.VOCAB else MineKind.SENTENCE,
        mediaId = mediaKey, mediaTitle = source.title, mediaKind = kind,
        startMs = line.startMs, endMs = line.endMs, sentence = line.japanese,
        wordStart = span?.first ?: 0, wordEnd = span?.let { it.last + 1 } ?: 0,
        reading = target?.targetReading,
        meanings = summary?.glossPreview?.split(';', '；')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        translation = line.english,
        entryId = target?.entryId,
    )
    val duration = try {
        ClipCutter.cut(context, source.uri, draft.startMs, draft.endMs, draft.audio.path)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        0L
    }
    val frame = draft.image?.let { img -> draft.thumbnailMs?.let { at -> BankClips.writeFrame(context, source.uri, at, img.path) } } ?: false
    graph.sentenceMiner.attachMedia(draft, duration, frame)
    context.getString(
        when {
            duration > 0 && (frame || draft.image == null) -> R.string.mine_done
            duration > 0 -> R.string.mine_done_no_frame
            else -> R.string.mine_done_no_audio
        },
    )
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    context.getString(R.string.mine_failed, e.readable())
}

/** A media item in the sentence bank with its coverage (when its subtitles have been profiled). */
private data class MediaRow(val media: IndexedMedia, val coverage: DocumentCoverage?)

/**
 * "Your media" (§6.1 library sort + §6.2): media whose subtitles are in the sentence bank, sorted by coverage (most
 * readable first; unprofiled last), with the difficulty badge. Opening one reuses its indexed cues.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MyMediaList(onOpen: (locator: String, cues: List<Cue>) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<MediaRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var byCoverage by remember { mutableStateOf(true) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) {
        runCatching {
            graph.sentenceBank.indexed().filter { it.locator != null }.map { m ->
                val profile = graph.coverage.profiles.get(CoverageService.mediaProfileKey(m.mediaId))
                MediaRow(m, profile?.let { graph.coverage.coverageOf(CoverageService.mediaProfileKey(m.mediaId), it) })
            }
        }.onSuccess { rows = it; error = null }.onFailure { error = it.readable() }
    }
    val list = rows ?: run {
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { version++ }) }
        return
    }
    if (list.isEmpty()) return
    Text(stringResource(R.string.media_mine), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(byCoverage, { byCoverage = true }, { Text(stringResource(R.string.sort_coverage)) })
        FilterChip(!byCoverage, { byCoverage = false }, { Text(stringResource(R.string.sort_recent)) })
    }
    val sorted = if (byCoverage) list.sortedWith(compareByDescending<MediaRow> { it.coverage != null }.thenByDescending { it.coverage?.coverage?.knownRatio ?: 0.0 })
    else list.sortedByDescending { it.media.indexedAt }
    sorted.forEach { row ->
        Card(Modifier.fillMaxWidth().clickable {
            scope.launch {
                val cues = runCatching { graph.sentenceBank.cues(row.media.mediaId) }.getOrDefault(emptyList())
                onOpen(row.media.locator!!, cues)
            }
        }) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                JaText(row.media.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                row.coverage?.let { c ->
                    Text(coverageSummary(c.coverage), style = MaterialTheme.typography.bodySmall)
                    DifficultyBadge(c.difficulty)
                } ?: Text(stringResource(R.string.media_lines, row.media.cueCount), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scope.launch { runCatching { graph.sentenceBank.remove(row.media.mediaId) }; version++ } }) {
                        Text(stringResource(R.string.media_forget))
                    }
                }
            }
        }
    }
}
