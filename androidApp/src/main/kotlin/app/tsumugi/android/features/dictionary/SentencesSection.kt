package app.tsumugi.android.features.dictionary

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.AudioFilePlayer
import app.tsumugi.android.platform.BankClips
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.android.ui.rememberBitmap
import app.tsumugi.dictionary.ExampleSentence
import app.tsumugi.media.MediaKind
import app.tsumugi.media.OnlineExamplesResult
import app.tsumugi.media.SentenceHit
import app.tsumugi.media.SentenceSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The entry's sentences (BRIEF_V2 §6.2, D-160): lines from the learner's own media first (clip playback and a
 * video frame, extracted on demand), then Tatoeba, then Immersion Kit when the learner turned it on (live, never
 * stored). [fallback] is the dictionary's own example list, shown if the search itself fails.
 */
@Composable
fun SentencesSection(entryId: Long, word: String, reading: String?, fallback: List<ExampleSentence>) {
    val graph = rememberGraph()
    var result by remember(entryId) { mutableStateOf<SentenceSearchResult?>(null) }
    var error by remember(entryId) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(entryId, attempt) {
        runCatching { graph.sentenceSearch.forEntry(entryId, word, reading) }
            .onSuccess { result = it; error = null }
            .onFailure { error = it.readable() }
    }
    Heading(stringResource(R.string.dict_sentences))
    val r = result
    if (r == null) {
        if (error != null) {
            ErrorState(stringResource(R.string.dict_sentences_failed, error!!), onRetry = { attempt++ })
            fallback.forEach { s -> PlainSentence(s.japanese, s.english) }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }
    Group(stringResource(R.string.dict_sentences_mine))
    if (r.library.isEmpty()) {
        Text(stringResource(R.string.dict_sentences_mine_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        r.library.forEach { LibraryHit(it) }
    }
    if (r.tatoeba.isNotEmpty()) {
        Group("Tatoeba")
        r.tatoeba.forEach { h -> Highlighted(h); h.english?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        Text(stringResource(R.string.dict_examples_credit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
    when (val online = r.online) {
        is OnlineExamplesResult.Found -> if (online.hits.isNotEmpty()) {
            Group(graph.onlineExamples.sourceName)
            online.hits.forEach { OnlineHit(it) }
            Text(stringResource(R.string.dict_online_note, graph.onlineExamples.sourceName), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        is OnlineExamplesResult.Failed -> Text(stringResource(R.string.dict_online_failed, graph.onlineExamples.sourceName, online.reason), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        else -> Unit // Off (the default): nothing is requested; the switch is in Settings.
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Group(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
}

@Composable
private fun PlainSentence(japanese: String, english: String?) {
    Column {
        Text(ja(japanese), style = MaterialTheme.typography.bodyLarge.japanese())
        english?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The sentence with the entry's word in bold. */
@Composable
private fun Highlighted(hit: SentenceHit, modifier: Modifier = Modifier) {
    val s = hit.japanese
    Text(
        ja(
            buildAnnotatedString {
                if (hit.hasHighlight) {
                    append(s.substring(0, hit.highlightStart))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(s.substring(hit.highlightStart, hit.highlightEnd)) }
                    append(s.substring(hit.highlightEnd))
                } else {
                    append(s)
                }
            },
        ),
        modifier,
        style = MaterialTheme.typography.bodyLarge.japanese(),
    )
}

private fun time(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** A line from the learner's own media: play the clip (cut on demand), and the frame for video. */
@Composable
private fun LibraryHit(hit: SentenceHit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clip = hit.clip
    val locator = hit.locator
    var job by remember { mutableStateOf<Job?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var framePath by remember(hit.mediaId, clip?.thumbnailMs) { mutableStateOf<String?>(null) }
    LaunchedEffect(hit.mediaId, clip?.thumbnailMs) {
        val at = clip?.thumbnailMs
        if (hit.mediaKind == MediaKind.VIDEO && at != null && locator != null && hit.mediaId != null) {
            framePath = runCatching { BankClips.frame(context, locator, hit.mediaId!!, at) }.getOrNull()?.path
        }
    }
    val frame = rememberBitmap(framePath, maxPx = 320)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        frame?.let { Image(it.asImageBitmap(), contentDescription = null, modifier = Modifier.width(96.dp)) }
        Column(Modifier.weight(1f)) {
            Highlighted(hit)
            Text(
                listOfNotNull(hit.mediaTitle, clip?.let { time(it.startMs) }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            failed?.let { Text(stringResource(R.string.dict_clip_failed, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
        if (clip != null && locator != null && hit.mediaId != null) {
            val label = stringResource(R.string.dict_play_clip)
            if (job != null) {
                IconButton(onClick = { job?.cancel() }, modifier = Modifier.semantics { contentDescription = label }) { CircularProgressIndicator(Modifier.width(20.dp)) }
            } else {
                IconButton(onClick = {
                    failed = null
                    job = scope.launch {
                        try {
                            val file = BankClips.audio(context, locator, hit.mediaId!!, clip)
                            AudioFilePlayer.play(file.path)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The file moved or its permission lapsed: say so rather than play nothing.
                            failed = e.readable()
                        } finally {
                            job = null
                        }
                    }
                }, modifier = Modifier.semantics { contentDescription = label }) { Text("▶") }
            }
        }
    }
}

/**
 * An Immersion Kit line: shown live with its source label (D-162). Its audio and picture aren't played or loaded:
 * that would stream third-party media without a timeout we control (rule 13), and nothing from it is stored.
 */
@Composable
private fun OnlineHit(hit: SentenceHit) {
    Column {
        Highlighted(hit)
        hit.english?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        hit.mediaTitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
