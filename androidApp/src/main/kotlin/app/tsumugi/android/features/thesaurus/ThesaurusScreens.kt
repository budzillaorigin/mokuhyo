package app.tsumugi.android.features.thesaurus

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.readable
import app.tsumugi.thesaurus.ClusterDetail
import app.tsumugi.thesaurus.ClusterKind
import app.tsumugi.thesaurus.ExpressionCluster
import app.tsumugi.thesaurus.ThesaurusExpression
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Localized name of an expression's register (casual | neutral | formal | literary). */
@Composable
internal fun expressionRegister(code: String): String = when (code) {
    "casual" -> stringResource(R.string.th_register_casual)
    "formal" -> stringResource(R.string.th_register_formal)
    "literary" -> stringResource(R.string.th_register_literary)
    else -> stringResource(R.string.th_register_neutral)
}

/**
 * The expression thesaurus (BRIEF_V2 §6.13, D-296): clusters of descriptive expressions by emotion or scene, with
 * search. Null repository = no dictionary pack; no clusters = a dictionary pack built before Phase 13.
 */
@Composable
fun ThesaurusScreen(onOpenCluster: (String) -> Unit) {
    val graph = rememberGraph()
    var kind by remember { mutableStateOf<ClusterKind?>(null) }
    var query by remember { mutableStateOf("") }
    var clusters by remember { mutableStateOf<List<ExpressionCluster>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(kind, query, attempt) {
        if (query.isNotBlank()) delay(200) // typing: wait for a pause
        error = null
        runCatching {
            val repo = graph.thesaurus()
            if (repo == null) {
                missing = true
                emptyList()
            } else {
                missing = false
                val found = if (query.isBlank()) repo.clusters(kind) else repo.search(query)
                found.filter { kind == null || it.kind == kind }
            }
        }.onSuccess { clusters = it }.onFailure { error = it.readable() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.th_intro), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.th_search)) },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(kind == null, { kind = null }, { Text(stringResource(R.string.filter_all)) })
            FilterChip(kind == ClusterKind.EMOTION, { kind = ClusterKind.EMOTION }, { Text(stringResource(R.string.th_kind_emotion)) })
            FilterChip(kind == ClusterKind.SCENE, { kind = ClusterKind.SCENE }, { Text(stringResource(R.string.th_kind_scene)) })
        }
        val list = clusters
        when {
            error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ })
            missing -> Notice(stringResource(R.string.th_no_dictionary))
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list.isEmpty() && query.isBlank() && kind == null -> Notice(stringResource(R.string.th_pack_old))
            list.isEmpty() -> Notice(stringResource(R.string.th_no_match))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { c -> ClusterRow(c) { onOpenCluster(c.id) } }
            }
        }
    }
}

@Composable
private fun ClusterRow(c: ExpressionCluster, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JaText(c.ja, style = MaterialTheme.typography.titleMedium)
                if (c.reading.isNotBlank() && c.reading != c.ja) JaText(c.reading, style = MaterialTheme.typography.bodySmall)
                if (c.isAiGenerated) AiBadge()
            }
        },
        supportingContent = { Text(listOf(c.en, c.description).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2) },
        trailingContent = { Tag(stringResource(if (c.kind == ClusterKind.EMOTION) R.string.th_kind_emotion else R.string.th_kind_scene)) },
    )
}

/** One cluster: its expressions with JMdict glosses, nuance, register and intensity, a drafted example and Tatoeba examples. */
@Composable
fun ThesaurusClusterScreen(id: String, onOpenEntry: (Long) -> Unit) {
    val graph = rememberGraph()
    var detail by remember(id) { mutableStateOf<ClusterDetail?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        error = null
        runCatching { graph.thesaurus()?.cluster(id) }.onSuccess { detail = it }.onFailure { error = it.readable() }
        loaded = true
    }
    val d = detail
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ })
            !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
            d == null -> Notice(stringResource(R.string.th_cluster_missing))
            else -> ClusterView(d, onOpenEntry)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClusterView(d: ClusterDetail, onOpenEntry: (Long) -> Unit) {
    val c = d.cluster
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        JaText(c.ja, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        if (c.reading.isNotBlank() && c.reading != c.ja) JaText(c.reading, style = MaterialTheme.typography.bodyMedium)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Tag(stringResource(if (c.kind == ClusterKind.EMOTION) R.string.th_kind_emotion else R.string.th_kind_scene))
        if (c.en.isNotBlank()) Tag(c.en)
        if (c.isAiGenerated) AiBadge()
    }
    if (c.description.isNotBlank()) Text(c.description, style = MaterialTheme.typography.bodyMedium)
    SectionTitle(stringResource(R.string.th_expressions, d.expressions.size))
    if (d.expressions.isEmpty()) Notice(stringResource(R.string.th_no_expressions))
    d.expressions.forEach { e -> ExpressionCard(e, c.isAiGenerated, onOpenEntry) }
    Text(stringResource(R.string.th_credit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpressionCard(e: ThesaurusExpression, drafted: Boolean, onOpenEntry: (Long) -> Unit) {
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JaText(
                    e.text,
                    if (e.entryId != null) Modifier.clickable { onOpenEntry(e.entryId!!) } else Modifier,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (e.entryId != null) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                )
                if (e.reading.isNotBlank() && e.reading != e.text) JaText(e.reading, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(expressionRegister(e.register))
                Tag(stringResource(R.string.th_intensity, "●".repeat(e.intensity.coerceIn(1, 3)) + "○".repeat(3 - e.intensity.coerceIn(1, 3))))
            }
            if (e.gloss.isNotBlank()) Text(e.gloss, style = MaterialTheme.typography.bodyMedium)
            if (e.nuance.isNotBlank() || e.exampleJa.isNotBlank()) {
                HorizontalDivider()
                if (e.nuance.isNotBlank()) Text(e.nuance, style = MaterialTheme.typography.bodySmall)
                if (e.exampleJa.isNotBlank()) {
                    JaText(e.exampleJa, style = MaterialTheme.typography.bodyLarge)
                    if (e.exampleEn.isNotBlank()) Text(e.exampleEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (drafted) AiBadge()
            }
            if (e.tatoeba.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.th_tatoeba), style = MaterialTheme.typography.labelMedium)
                e.tatoeba.forEach { ex ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            JaText(ex.ja, style = MaterialTheme.typography.bodyMedium)
                            Text(ex.en, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { scope.launch { runCatching { voices.say(ex.ja) } } }) { PlayLabel(stringResource(R.string.th_play)) }
                    }
                }
            }
        }
    }
}
