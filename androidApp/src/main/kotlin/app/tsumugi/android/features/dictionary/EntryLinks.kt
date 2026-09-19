package app.tsumugi.android.features.dictionary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.readable
import app.tsumugi.thesaurus.CollocationPair
import app.tsumugi.thesaurus.CollocationPattern
import app.tsumugi.thesaurus.ExpressionCluster
import kotlinx.coroutines.CancellationException

/** What the entry's thesaurus links hold once loaded. Null thesaurus = the dictionary pack predates Phase 13. */
private sealed interface LinksLoad {
    data object Loading : LinksLoad
    data class Failed(val message: String) : LinksLoad
    data class Ready(val available: Boolean, val clusters: List<ExpressionCluster>, val collocations: List<CollocationPair>) : LinksLoad
}

/**
 * The entry's Phase 13 links (BRIEF_V2 §6.13, §6.15): "Explore graph" for a word written with kanji, the thesaurus
 * clusters that list it (tap to open), and its strongest collocations from the Tatoeba corpus, filtered by pattern,
 * each with an example sentence. Sections without data are left out; a pack without the tables says so once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EntryLinksSection(entryId: Long, hasKanji: Boolean, nav: DictionaryNav) {
    val graph = rememberGraph()
    var attempt by remember(entryId) { mutableIntStateOf(0) }
    var load by remember(entryId) { mutableStateOf<LinksLoad>(LinksLoad.Loading) }
    LaunchedEffect(entryId, attempt) {
        load = LinksLoad.Loading
        load = try {
            val thesaurus = graph.thesaurus()
            if (thesaurus == null) {
                LinksLoad.Ready(false, emptyList(), emptyList())
            } else {
                val ids = thesaurus.clustersForEntry(entryId).toSet()
                val clusters = if (ids.isEmpty()) emptyList() else thesaurus.clusters().filter { it.id in ids }
                LinksLoad.Ready(thesaurus.available(), clusters, thesaurus.collocations(entryId, limit = 40))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LinksLoad.Failed(e.readable())
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hasKanji) {
            OutlinedButton(onClick = { nav.openWordGraph(entryId) }) { Text(stringResource(R.string.kx_explore_word_graph)) }
        }
        when (val l = load) {
            LinksLoad.Loading -> Unit
            is LinksLoad.Failed -> ErrorState(stringResource(R.string.dp_links_failed, l.message), onRetry = { attempt++ })
            is LinksLoad.Ready -> {
                if (l.clusters.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinkHeading(stringResource(R.string.dp_expressions))
                        // Rule 10: the clusters are our own drafts until reviewed.
                        if (l.clusters.any { it.isAiGenerated }) AiBadge()
                    }
                    Text(stringResource(R.string.dp_expressions_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        l.clusters.forEach { c ->
                            AssistChip(
                                onClick = { nav.openCluster(c.id) },
                                label = { JaText(c.ja + if (c.en.isNotBlank()) " · ${c.en}" else "") },
                            )
                        }
                    }
                }
                if (l.collocations.isNotEmpty()) {
                    CollocationsBlock(entryId, l.collocations, nav)
                } else if (l.available) {
                    LinkHeading(stringResource(R.string.dp_collocations))
                    Text(stringResource(R.string.dp_collocations_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CollocationsBlock(entryId: Long, all: List<CollocationPair>, nav: DictionaryNav) {
    var pattern by remember(entryId) { mutableStateOf<CollocationPattern?>(null) }
    var expanded by remember(entryId) { mutableStateOf(false) }
    val patterns = CollocationPattern.entries.filter { p -> all.any { it.pattern == p } }
    val shown = all.filter { pattern == null || it.pattern == pattern }
    LinkHeading(stringResource(R.string.dp_collocations))
    if (patterns.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(pattern == null, { pattern = null }, { Text(stringResource(R.string.filter_all)) })
            patterns.forEach { p -> FilterChip(pattern == p, { pattern = p }, { Text(patternLabel(p)) }) }
        }
    }
    (if (expanded) shown else shown.take(COLLAPSED)).forEach { c ->
        // Tapping opens the partner word (the one that isn't this entry).
        val partner = if (c.firstId == entryId) c.secondId else c.firstId
        Column(Modifier.fillMaxWidth().clickable { nav.openEntry(partner) }.padding(vertical = 4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JaText(c.phrase, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dp_collocation_count, c.count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            c.example?.let { ex ->
                JaText(ex.ja, style = MaterialTheme.typography.bodyMedium)
                if (ex.en.isNotBlank()) Text(ex.en, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (shown.size > COLLAPSED) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) stringResource(R.string.dp_show_fewer) else stringResource(R.string.dp_show_all, shown.size))
        }
    }
    Text(stringResource(R.string.dp_collocations_credit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun patternLabel(p: CollocationPattern): String = stringResource(
    when (p) {
        CollocationPattern.NV -> R.string.dp_pattern_nv
        CollocationPattern.AN -> R.string.dp_pattern_an
        CollocationPattern.AV -> R.string.dp_pattern_av
    },
)

@Composable
private fun LinkHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

private const val COLLAPSED = 8
