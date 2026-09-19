package app.tsumugi.android.features.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.readable
import app.tsumugi.coverage.DifficultyScore
import app.tsumugi.coverage.DocumentCoverage
import app.tsumugi.coverage.KnownWords
import app.tsumugi.coverage.TextCoverage
import app.tsumugi.coverage.WordState
import kotlinx.coroutines.launch

/** "N3 · ILR 1+", with "above N1" localized. */
@Composable
fun levelLabel(jlpt: Int, ilr: String): String =
    stringResource(R.string.level_label, if (jlpt == 0) stringResource(R.string.level_above_n1) else "N$jlpt", ilr)

/** The §6.4 difficulty as a compact badge: the 0–100 score and its JLPT/ILR label (D-156). */
@Composable
fun DifficultyBadge(score: DifficultyScore, modifier: Modifier = Modifier) {
    val label = levelLabel(score.jlpt, score.ilr)
    val description = stringResource(R.string.difficulty_description, score.score, label)
    Surface(
        modifier.semantics { contentDescription = description },
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(stringResource(R.string.difficulty_badge, score.score, label), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** "You know X% of the words · Y% of the kanji · N new words to reach 95%", localized from the shared numbers. */
@Composable
fun coverageSummary(c: TextCoverage): String =
    if (c.newWordsTo95 > 0) stringResource(R.string.coverage_summary_to95, c.knownPercent, c.kanjiPercent, c.newWordsTo95)
    else stringResource(R.string.coverage_summary, c.knownPercent, c.kanjiPercent)

/**
 * The coverage overlay (BRIEF_V2 §6.1) for a reader document or a media item: how much the learner already knows,
 * the difficulty badge, and actions (make a deck). [load] computes it (tokenizing once, with progress).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CoverageOverlay(
    key: Any,
    load: suspend (onProgress: (Double) -> Unit) -> DocumentCoverage?,
    modifier: Modifier = Modifier,
    onCreateDeck: (() -> Unit)? = null,
) {
    var coverage by remember(key) { mutableStateOf<DocumentCoverage?>(null) }
    var progress by remember(key) { mutableStateOf<Double?>(0.0) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var attempt by remember(key) { mutableStateOf(0) }
    var unavailable by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, attempt) {
        error = null
        progress = 0.0
        runCatching { load { p -> progress = p } }
            .onSuccess { coverage = it; unavailable = it == null }
            .onFailure { error = it.readable() }
        progress = null
    }
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val c = coverage
            when {
                error != null -> {
                    Text(stringResource(R.string.coverage_failed, error!!), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.action_retry)) }
                }
                c != null -> {
                    Text(coverageSummary(c.coverage), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DifficultyBadge(c.difficulty)
                        Text(stringResource(R.string.coverage_unique, c.uniqueWords), style = MaterialTheme.typography.labelMedium)
                        if (c.sampled) Text(stringResource(R.string.coverage_sampled), style = MaterialTheme.typography.labelMedium)
                    }
                    onCreateDeck?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.deck_create)) } }
                }
                unavailable -> Text(stringResource(R.string.coverage_no_pack), style = MaterialTheme.typography.bodySmall)
                else -> {
                    Text(stringResource(R.string.coverage_computing, ((progress ?: 0.0) * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { (progress ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/**
 * "Mark known" (BRIEF_V2 §6.1, D-151): the word counts as known for coverage and decks without an SRS item. A mis-tap
 * can be undone ("unmark", last-writer-wins across devices).
 */
@Composable
fun MarkKnownButton(entryId: Long, text: String? = null, onChanged: (Boolean) -> Unit = {}) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember(entryId) { mutableStateOf<WordState?>(null) }
    var note by remember(entryId) { mutableStateOf<String?>(null) }
    LaunchedEffect(entryId) { state = runCatching { graph.knownWords.state(entryId) }.getOrNull() }
    val known = state == WordState.KNOWN
    Column {
        OutlinedButton(
            onClick = {
                scope.launch {
                    runCatching {
                        if (known) graph.knownWords.markUnknown(listOf(entryId)) else graph.knownWords.markKnown(listOf(entryId), KnownWords.SOURCE_MANUAL)
                        graph.knownWords.state(entryId)
                    }.onSuccess {
                        state = it
                        note = context.getString(if (it == WordState.KNOWN) R.string.known_marked else R.string.known_unmarked)
                        onChanged(it == WordState.KNOWN)
                    }.onFailure { note = it.readable() }
                }
            },
            enabled = state != null,
        ) { Text(stringResource(if (known) R.string.known_unmark else R.string.known_mark)) }
        note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun wordStateLabel(state: WordState): String = stringResource(
    when (state) {
        WordState.KNOWN -> R.string.word_known
        WordState.LEARNING -> R.string.word_learning
        WordState.UNKNOWN -> R.string.word_new
    },
)
