package app.tsumugi.android.features.kanji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.dictionary.KanjiInfo
import app.tsumugi.kanji.ComponentRole
import app.tsumugi.kanji.KanjiComponent
import app.tsumugi.kanji.SoundSeries

private data class KanjiExtras(val components: List<KanjiComponent>, val series: SoundSeries?)

/**
 * The kanji page's §6.15 part (D-292): "Explore graph", bookmark to SRS, component search from its parts, the
 * functional components (semantic / phonetic / form, "derived" until reviewed) and the sound series it belongs to.
 * A pack built before Phase 13 has no roles: the KRADFILE parts ([fallbackComponents]) show as before.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KanjiExplorerSections(
    info: KanjiInfo,
    fallbackComponents: List<String>,
    onOpenKanji: (String) -> Unit,
    onOpenGraph: (String) -> Unit,
    onComponentSearch: (String) -> Unit,
) {
    val graph = rememberGraph()
    val literal = info.literal
    var attempt by remember(literal) { mutableIntStateOf(0) }
    var state by remember(literal) { mutableStateOf<ExplorerLoad<KanjiExtras>>(ExplorerLoad.Loading) }
    LaunchedEffect(literal, attempt) {
        state = ExplorerLoad.Loading
        state = loadExplorer({ graph.kanjiExplorer() }) { KanjiExtras(it.components(literal).filter { c -> c.component != literal }, it.seriesOf(literal)) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onOpenGraph(literal) }) { Text(stringResource(R.string.kx_explore_graph)) }
            BookmarkButton(literal)
        }
        when (val s = state) {
            ExplorerLoad.Loading -> Unit
            ExplorerLoad.Missing -> FallbackParts(fallbackComponents, onOpenKanji)
            is ExplorerLoad.Failed -> {
                ErrorState(stringResource(R.string.error_loading, s.message), onRetry = { attempt++ })
                FallbackParts(fallbackComponents, onOpenKanji)
            }
            is ExplorerLoad.Ready -> {
                val comps = s.value.components
                if (comps.isEmpty()) {
                    FallbackParts(fallbackComponents, onOpenKanji)
                } else {
                    Heading(stringResource(R.string.dict_components))
                    if (comps.any { it.role != null }) {
                        Text(stringResource(R.string.kx_roles_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    comps.forEach { c -> ComponentRow(c, onOpenKanji) }
                }
                if (comps.isNotEmpty() || fallbackComponents.isNotEmpty()) {
                    ComponentQueryButton(literal, onComponentSearch)
                }
                s.value.series?.let { series ->
                    Heading(stringResource(R.string.kx_sound_series))
                    Text(stringResource(R.string.kx_sound_series_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SeriesCard(series, current = literal, onOpenKanji = onOpenKanji)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FallbackParts(parts: List<String>, onOpenKanji: (String) -> Unit) {
    if (parts.isEmpty()) return
    Heading(stringResource(R.string.dict_components))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { c -> AssistChip(onClick = { onOpenKanji(c) }, label = { JaText(c, style = MaterialTheme.typography.titleMedium) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComponentRow(c: KanjiComponent, onOpenKanji: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpenKanji(c.component) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        JaText(c.component, Modifier.widthIn(min = 40.dp), style = MaterialTheme.typography.headlineSmall)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                c.role?.let { Tag(roleLabel(it)) }
                if (c.derived && c.role != null) DerivedTag()
                if (c.kanjiVgPhonetic) Tag(stringResource(R.string.kx_kanjivg_phon))
            }
            val detail = when (c.role) {
                ComponentRole.PHONETIC -> listOfNotNull(
                    c.reading?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.kx_gives_sound, it) },
                    matchLabel(c.match).takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                ComponentRole.SEMANTIC -> stringResource(R.string.kx_semantic_hint)
                ComponentRole.FORM -> stringResource(R.string.kx_form_hint)
                null -> ""
            }
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}
