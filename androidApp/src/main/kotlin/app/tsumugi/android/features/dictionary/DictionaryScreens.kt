package app.tsumugi.android.features.dictionary

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
                Text("Preparing dictionary…")
            }
        }
        DictionaryState.NotInstalled -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
            Text(
                "The dictionary pack isn't installed in this build.\n\nBuild it with `uv run packs/build_all.py` in tools/, then rebuild the app.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        is DictionaryState.Ready -> content(state.repository)
    }
}

@Composable
fun DictionarySearchScreen(nav: DictionaryNav, initialQuery: String? = null) {
    val vm: DictionaryViewModel = viewModel(key = initialQuery ?: "search")
    LaunchedEffect(initialQuery) { if (initialQuery != null) vm.query.value = initialQuery }
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()

    DictionaryGate {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("漢字, かな, romaji, English, or a sentence") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.japanese(),
                )
                TextButton(onClick = nav.openRadicals) { Text("部首") }
            }
            if (results.mode == SearchMode.SENTENCE) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    results.tokens.forEach { t ->
                        AssistChip(
                            onClick = { t.entryId?.let(nav.openEntry) },
                            enabled = t.entryId != null,
                            label = { Text(t.surface, style = MaterialTheme.typography.bodyLarge.japanese()) },
                        )
                    }
                }
            }
            if (query.isNotBlank() && results.hits.isEmpty() && results.query == query.trim()) {
                Text("No matches", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(hit.entry.headword, style = MaterialTheme.typography.titleLarge.japanese())
                if (hit.entry.reading != hit.entry.headword) {
                    Text(hit.entry.reading, style = MaterialTheme.typography.bodyMedium.japanese(), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (hit.entry.isCommon) Tag("common")
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
            loaded -> Text("Not found", Modifier.padding(24.dp))
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
                if (e.isCommon) Tag("common")
                jlptLabel(e.jlpt)?.let { Tag("$it (unofficial)") }
            }
            if (detail.pitch.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    detail.pitch.forEach { PitchDiagram(e.reading, it) }
                }
            }
            if (e.kanji.size > 1 || e.kana.size > 1) {
                Text(
                    "Also: " + (e.kanji.drop(1).map { it.text } + e.kana.drop(1).map { it.text }).joinToString("、"),
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
                Section("Kanji")
                detail.kanji.forEach { k ->
                    Card(Modifier.fillMaxWidth().clickable { nav.openKanji(k.literal) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(k.literal, style = MaterialTheme.typography.displaySmall.japanese())
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(k.meanings.take(4).joinToString(", "), fontWeight = FontWeight.Medium)
                                Text((k.onyomi + k.kunyomi).take(6).joinToString("、"), style = MaterialTheme.typography.bodyMedium.japanese())
                            }
                        }
                    }
                }
            }
            if (detail.conjugations.isNotEmpty()) {
                Section("Conjugation")
                detail.conjugations.forEach { c ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(c.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(c.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge.japanese())
                    }
                    HorizontalDivider()
                }
            }
            if (detail.sentences.isNotEmpty()) {
                Section("Examples")
                detail.sentences.forEach { s ->
                    Column {
                        Text(s.japanese, style = MaterialTheme.typography.bodyLarge.japanese())
                        Text(s.english, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Examples: Tatoeba (CC BY 2.0 FR)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (k.strokes.isNotEmpty()) StrokeOrderView(k.strokes, Modifier.size(180.dp))
                else Text(info.literal, style = MaterialTheme.typography.displayLarge.japanese())
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(info.keyword, style = MaterialTheme.typography.titleLarge)
                    Text("${info.strokeCount} strokes", style = MaterialTheme.typography.bodyMedium)
                    info.grade?.let { Text(if (it <= 6) "Jōyō grade $it" else if (it == 8) "Jōyō (secondary)" else "Jinmeiyō", style = MaterialTheme.typography.bodyMedium) }
                    jlptLabel(info.jlpt)?.let { Text("JLPT $it (unofficial)", style = MaterialTheme.typography.bodyMedium) }
                    info.heisig6?.let { Text("Heisig #$it", style = MaterialTheme.typography.bodyMedium) }
                    if (k.strokes.isNotEmpty()) Text("Tap to replay", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Text(info.meanings.joinToString(", "), style = MaterialTheme.typography.bodyLarge)
            if (info.onyomi.isNotEmpty()) LabeledJa("On", info.onyomi.joinToString("、"))
            if (info.kunyomi.isNotEmpty()) LabeledJa("Kun", info.kunyomi.joinToString("、"))
            if (info.nanori.isNotEmpty()) LabeledJa("Names", info.nanori.joinToString("、"))
            if (k.components.isNotEmpty()) {
                Section("Components")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    k.components.forEach { c -> AssistChip(onClick = { nav.openKanji(c) }, label = { Text(c, style = MaterialTheme.typography.titleMedium.japanese()) }) }
                }
            }
            if (k.words.isNotEmpty()) {
                Section("Words")
                k.words.forEach { w ->
                    Row(Modifier.fillMaxWidth().clickable { nav.openEntry(w.id) }.padding(vertical = 6.dp)) {
                        Text(w.headword, Modifier.width(110.dp), style = MaterialTheme.typography.bodyLarge.japanese())
                        Column(Modifier.weight(1f)) {
                            Text(w.reading, style = MaterialTheme.typography.bodySmall.japanese(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(w.glossPreview, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Text("Stroke order: KanjiVG (CC BY-SA 3.0) · KANJIDIC2 (EDRDG, CC BY-SA 4.0)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                if (selected.isEmpty()) Text("Pick radicals to find a kanji", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                kanji.take(120).forEach { k ->
                    Text(k.literal, Modifier.clickable { nav.openKanji(k.literal) }.padding(8.dp), style = MaterialTheme.typography.headlineMedium.japanese())
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
                        Box(Modifier.size(40.dp), Alignment.Center) { Text("${r.strokeCount}", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
                    }
                    val isSelected = r.radical in selected
                    val enabled = isSelected || selected.isEmpty() || r.radical in result?.compatibleRadicals.orEmpty()
                    FilterChip(
                        selected = isSelected,
                        enabled = enabled,
                        onClick = { selected = if (isSelected) selected - r.radical else selected + r.radical },
                        label = { Text(r.display, style = MaterialTheme.typography.titleMedium.japanese()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun LabeledJa(label: String, value: String) {
    Row {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge.japanese())
    }
}
