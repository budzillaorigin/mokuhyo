package app.tsumugi.android.features.decks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.readable
import app.tsumugi.coverage.FrequencyBand
import app.tsumugi.coverage.FrequencyBatch
import app.tsumugi.coverage.KnownWords
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * "I know these" (BRIEF_V2 §6.1, jpdb's mark-known flow, D-151): batches from the frequency list, most common first,
 * skipping words already known. The learner taps the words they know; they count as known for coverage, decks and
 * lessons, so an intermediate learner isn't drilled on 猫. Start points jump to later frequency bands. Used in
 * onboarding (optional, for non-beginners) and from Decks.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KnownWordsStep(onDone: () -> Unit, source: String = KnownWords.SOURCE_ONBOARDING, doneLabel: String = stringResource(R.string.action_done)) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var after by remember { mutableIntStateOf(0) }
    var batch by remember { mutableStateOf<FrequencyBatch?>(null) }
    var bands by remember { mutableStateOf<List<FrequencyBand>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var marked by remember { mutableIntStateOf(0) }
    val picked = remember { mutableStateListOf<Long>() }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(after, version) {
        busy = true
        picked.clear()
        runCatching {
            batch = graph.knownWords.frequencyBatch(after, 40)
            bands = graph.knownWords.bands()
        }.onSuccess { error = null }.onFailure { error = it.readable() }
        busy = false
    }
    fun commit(ids: List<Long>) {
        val next = batch?.nextAfter ?: return
        busy = true
        scope.launch {
            runCatching { if (ids.isNotEmpty()) graph.knownWords.markKnown(ids, source) }
                .onSuccess { marked += ids.size; after = next; version++ }
                .onFailure { error = it.readable(); busy = false }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.known_step_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.known_step_intro))
        if (bands.isNotEmpty()) {
            Text(stringResource(R.string.known_step_start), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                bands.forEach { b ->
                    FilterChip(
                        selected = after in (b.from - 1) until b.to,
                        onClick = { after = b.from - 1 },
                        label = { Text(stringResource(R.string.known_band, b.from, b.to, b.known, b.total)) },
                    )
                }
            }
        }
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { version++ }) }
        val b = batch
        when {
            b == null || (busy && b.words.isEmpty()) -> LinearProgressIndicator(Modifier.fillMaxWidth())
            b.words.isEmpty() && b.exhausted && bands.isEmpty() -> Text(stringResource(R.string.known_step_no_pack), style = MaterialTheme.typography.bodyMedium)
            b.words.isEmpty() -> Text(stringResource(R.string.known_step_all_known), style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(stringResource(R.string.known_step_tap), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    b.words.forEach { w ->
                        val on = w.entryId in picked
                        FilterChip(
                            selected = on,
                            onClick = { if (on) picked.remove(w.entryId) else picked.add(w.entryId) },
                            label = {
                                Column {
                                    Text(ja(w.headword), style = MaterialTheme.typography.titleMedium)
                                    if (w.reading != w.headword) Text(ja(w.reading), style = MaterialTheme.typography.labelSmall)
                                }
                            },
                        )
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { commit(picked.toList()) }, enabled = !busy) { Text(stringResource(R.string.known_step_mark_next, picked.size)) }
                    OutlinedButton(onClick = { commit(b.words.map { it.entryId }) }, enabled = !busy) { Text(stringResource(R.string.known_step_all)) }
                }
            }
        }
        if (marked > 0) Text(stringResource(R.string.known_step_marked, marked), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.tertiary)
        TextButton(onClick = onDone) { Text(doneLabel) }
    }
}
