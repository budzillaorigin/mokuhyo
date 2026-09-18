package app.tsumugi.android.features.dictionary

import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.ui.FuriganaText
import app.tsumugi.android.ui.PitchDiagram
import app.tsumugi.android.ui.StrokeOrderView
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.TagRow
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.jlptLabel
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.EntryDetail
import app.tsumugi.dictionary.KanjiDetail
import app.tsumugi.dictionary.MatchKind
import app.tsumugi.dictionary.Radical
import app.tsumugi.dictionary.RadicalSearchResult
import app.tsumugi.dictionary.SearchHit
import app.tsumugi.dictionary.SearchMode

/** Navigation callbacks for the dictionary screens (the Learn tab owns the back stack). */
class DictionaryNav(
    val openEntry: (Long) -> Unit,
    val openKanji: (String) -> Unit,
    val openRadicals: () -> Unit,
)

@Composable
fun DictionaryGate(content: @Composable (DictionaryRepository) -> Unit) {
    val vm: DictionaryViewModel = viewModel()
    when (val state = vm.state.collectAsStateWithLifecycle().value) {
        DictionaryState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.dict_preparing))
            }
        }
        DictionaryState.NotInstalled -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            Text(
                stringResource(R.string.dict_missing),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        is DictionaryState.Ready -> content(state.repository)
        is DictionaryState.Failed -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            ErrorState(stringResource(R.string.error_loading, state.message), onRetry = vm::open)
        }
    }
}

@Composable
fun DictionarySearchScreen(nav: DictionaryNav, initialQuery: String? = null) {
    val vm: DictionaryViewModel = viewModel(key = initialQuery ?: "search")
    LaunchedEffect(initialQuery) { if (initialQuery != null) vm.query.value = initialQuery }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val searchError by vm.searchError.collectAsStateWithLifecycle()

    DictionaryGate {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.dict_placeholder)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.japanese(),
                )
                val radicalsLabel = stringResource(R.string.title_radicals)
                TextButton(onClick = nav.openRadicals, Modifier.semantics { contentDescription = radicalsLabel }) { JaText("部首") }
            }
            if (results.mode == SearchMode.SENTENCE) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    results.tokens.forEach { t ->
                        AssistChip(
                            onClick = { t.entryId?.let(nav.openEntry) },
                            enabled = t.entryId != null,
                            label = { JaText(t.surface, style = MaterialTheme.typography.bodyLarge) },
                        )
                    }
                }
            }
            searchError?.let { ErrorState(stringResource(R.string.dict_search_failed, it), onRetry = vm::retrySearch, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (searchError == null && query.isNotBlank() && results.hits.isEmpty() && results.query == query.trim()) {
                Text(stringResource(R.string.dict_no_matches), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(results.hits, key = { it.entry.id }) { hit -> SearchHitRow(hit) { nav.openEntry(hit.entry.id) } }
            }
        }
    }
}

@Composable
private fun SearchHitRow(hit: SearchHit, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JaText(hit.entry.headword, style = MaterialTheme.typography.titleLarge)
                if (hit.entry.reading != hit.entry.headword) {
                    JaText(hit.entry.reading, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        supportingContent = {
            Column {
                Text(hit.entry.glossPreview, maxLines = 2)
                if (hit.match == MatchKind.DEINFLECTED) {
                    Text("← " + hit.deinflection.joinToString(" ← "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hit.entry.isCommon) Tag(stringResource(R.string.dict_common))
                jlptLabel(hit.entry.jlpt)?.let { Tag(it) }
            }
        },
    )
}

@Composable
private fun <T> Loaded(load: suspend (DictionaryRepository) -> T?, key: Any, content: @Composable (T) -> Unit) {
    DictionaryGate { repo ->
        var value by remember(key) { mutableStateOf<T?>(null) }
        var loaded by remember(key) { mutableStateOf(false) }
        LaunchedEffect(key) {
            value = load(repo)
            loaded = true
        }
        val v = value
        when {
            v != null -> content(v)
            loaded -> Text(stringResource(R.string.dict_not_found), Modifier.padding(24.dp))
            else -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryScreen(id: Long, nav: DictionaryNav) {
    Loaded(load = { it.entry(id) }, key = id) { detail: EntryDetail ->
        val e = detail.entry
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FuriganaText(detail.furigana)
            EntryActions(e)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (e.isCommon) Tag(stringResource(R.string.dict_common))
                jlptLabel(e.jlpt)?.let { Tag(stringResource(R.string.dict_unofficial, it)) }
            }
            if (detail.pitch.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    detail.pitch.forEach { PitchDiagram(e.reading, it) }
                }
            }
            if (e.kanji.size > 1 || e.kana.size > 1) {
                Text(
                    buildAnnotatedString {
                        append(stringResource(R.string.dict_also))
                        append(ja((e.kanji.drop(1).map { it.text } + e.kana.drop(1).map { it.text }).joinToString("、")))
                    },
                    style = MaterialTheme.typography.bodyMedium.japanese(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                e.senses.forEachIndexed { i, s ->
                    Column {
                        if (s.partsOfSpeech.isNotEmpty() && (i == 0 || s.partsOfSpeech != e.senses[i - 1].partsOfSpeech)) {
                            Text(s.partsOfSpeech.joinToString(", "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text("${i + 1}. ${s.glosses.joinToString("; ")}", style = MaterialTheme.typography.bodyLarge)
                        val notes = s.misc + s.fields + s.dialects + s.info
                        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (detail.kanji.isNotEmpty()) {
                Section(stringResource(R.string.kind_kanji))
                detail.kanji.forEach { k ->
                    Card(Modifier.fillMaxWidth().clickable { nav.openKanji(k.literal) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            JaText(k.literal, style = MaterialTheme.typography.displaySmall)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(k.meanings.take(4).joinToString(", "), fontWeight = FontWeight.Medium)
                                JaText((k.onyomi + k.kunyomi).take(6).joinToString("、"), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            if (detail.conjugations.isNotEmpty()) {
                Section(stringResource(R.string.dict_conjugation))
                detail.conjugations.forEach { c ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        JaText(c.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider()
                }
            }
            if (detail.sentences.isNotEmpty()) {
                Section(stringResource(R.string.dict_examples))
                detail.sentences.forEach { s ->
                    Column {
                        JaText(s.japanese, style = MaterialTheme.typography.bodyLarge)
                        Text(s.english, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(stringResource(R.string.dict_examples_credit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KanjiScreen(literal: String, nav: DictionaryNav) {
    Loaded(load = { it.kanji(literal) }, key = literal) { k: KanjiDetail ->
        val info = k.info
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (k.strokes.isNotEmpty()) StrokeOrderView(k.strokes, Modifier.size(180.dp), contentDescription = stringResource(R.string.dict_stroke_order_description, info.literal, info.strokeCount))
                else JaText(info.literal, style = MaterialTheme.typography.displayLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(info.keyword, style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.dict_strokes, info.strokeCount), style = MaterialTheme.typography.bodyMedium)
                    info.grade?.let { Text(if (it <= 6) stringResource(R.string.dict_joyo_grade, it) else if (it == 8) stringResource(R.string.dict_joyo_secondary) else stringResource(R.string.dict_jinmeiyo), style = MaterialTheme.typography.bodyMedium) }
                    jlptLabel(info.jlpt)?.let { Text(stringResource(R.string.dict_unofficial, "JLPT $it"), style = MaterialTheme.typography.bodyMedium) }
                    info.heisig6?.let { Text("Heisig #$it", style = MaterialTheme.typography.bodyMedium) }
                    if (k.strokes.isNotEmpty()) Text(stringResource(R.string.dict_tap_replay), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Text(info.meanings.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
            if (info.onyomi.isNotEmpty()) LabeledJa(stringResource(R.string.dict_onyomi), info.onyomi.joinToString("、"))
            if (info.kunyomi.isNotEmpty()) LabeledJa(stringResource(R.string.dict_kunyomi), info.kunyomi.joinToString("、"))
            if (info.nanori.isNotEmpty()) LabeledJa(stringResource(R.string.dict_nanori), info.nanori.joinToString("、"))
            if (k.components.isNotEmpty()) {
                Section(stringResource(R.string.dict_components))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    k.components.forEach { c -> AssistChip(onClick = { nav.openKanji(c) }, label = { JaText(c, style = MaterialTheme.typography.titleMedium) }) }
                }
            }
            if (k.words.isNotEmpty()) {
                Section(stringResource(R.string.dict_words))
                k.words.forEach { w ->
                    Row(Modifier.fillMaxWidth().clickable { nav.openEntry(w.id) }.padding(vertical = 6.dp)) {
                        JaText(w.headword, Modifier.widthIn(min = 110.dp).padding(end = 8.dp), style = MaterialTheme.typography.bodyLarge)
                        Column(Modifier.weight(1f)) {
                            JaText(w.reading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(w.glossPreview, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Text(stringResource(R.string.dict_kanji_credit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadicalSearchScreen(nav: DictionaryNav) {
    DictionaryGate { repo ->
        var radicals by remember { mutableStateOf<List<Radical>>(emptyList()) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var result by remember { mutableStateOf<RadicalSearchResult?>(null) }
        LaunchedEffect(Unit) { radicals = repo.radicals() }
        LaunchedEffect(selected) { result = repo.kanjiByRadicals(selected) }

        Column(Modifier.fillMaxSize()) {
            val kanji = result?.kanji.orEmpty()
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.isEmpty()) Text(stringResource(R.string.dict_pick_radicals), Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                kanji.take(120).forEach { k ->
                    JaText(k.literal, Modifier.clickable { nav.openKanji(k.literal) }.padding(8.dp), style = MaterialTheme.typography.headlineMedium)
                }
            }
            HorizontalDivider()
            FlowRow(
                Modifier.verticalScroll(rememberScrollState()).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                var lastCount = -1
                radicals.forEach { r ->
                    if (r.strokeCount != lastCount) {
                        lastCount = r.strokeCount
                        Box(Modifier.sizeIn(minWidth = 40.dp, minHeight = 40.dp), Alignment.Center) { Text("${r.strokeCount}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
                    }
                    val isSelected = r.radical in selected
                    val enabled = isSelected || selected.isEmpty() || r.radical in result?.compatibleRadicals.orEmpty()
                    FilterChip(
                        selected = isSelected,
                        enabled = enabled,
                        onClick = { selected = if (isSelected) selected - r.radical else selected + r.radical },
                        label = { JaText(r.display, style = MaterialTheme.typography.titleMedium) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LabeledJa(label: String, value: String) {
    Row {
        Text(label, Modifier.widthIn(min = 64.dp).padding(end = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        JaText(value, style = MaterialTheme.typography.bodyLarge)
    }
}
