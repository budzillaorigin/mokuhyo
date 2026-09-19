package app.tsumugi.android.features.poetry

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.poetry.AozoraSource
import app.tsumugi.poetry.PoemDetail
import app.tsumugi.poetry.PoemSummary
import app.tsumugi.poetry.PoemTheme
import app.tsumugi.reader.RubyHint
import kotlinx.coroutines.launch

/**
 * Text with the pack's ruby hints over their base runs, wrapping like normal text (each plain character is its own
 * cell). [offset] is where [text] starts in the body the hints index.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RubyText(text: String, offset: Int, ruby: List<RubyHint>, showRuby: Boolean, style: TextStyle, modifier: Modifier = Modifier) {
    val hints = ruby.filter { it.start >= offset && it.start + it.base.length <= offset + text.length && it.base.isNotEmpty() }.sortedBy { it.start }
    if (!showRuby || hints.isEmpty()) {
        JaText(text, modifier, style = style)
        return
    }
    val cells = ArrayList<Pair<String, String?>>()
    var at = 0
    for (h in hints) {
        val local = h.start - offset
        if (local < at) continue
        text.substring(at, local).forEach { cells += it.toString() to null }
        cells += h.base to h.reading
        at = local + h.base.length
    }
    text.substring(at).forEach { cells += it.toString() to null }
    FlowRow(modifier.clearAndSetSemantics { this.text = ja(text) }, verticalArrangement = Arrangement.Bottom) {
        cells.forEach { (base, rt) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    rt ?: " ",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = (style.fontSize.value.takeIf { !it.isNaN() } ?: 16f).times(0.5f).sp).japanese(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(base, style = style.japanese())
            }
        }
    }
}

/** The poetry corner (BRIEF_V2 §6.14, D-276): public-domain modern poems by theme. */
@Composable
fun PoetryScreen(onOpenPoem: (String) -> Unit) {
    val graph = rememberGraph()
    var theme by remember { mutableStateOf<String?>(null) }
    var themes by remember { mutableStateOf<List<PoemTheme>>(emptyList()) }
    var poems by remember { mutableStateOf<List<PoemSummary>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(theme, attempt) {
        error = null
        runCatching {
            val repo = graph.poetry()
            missing = repo == null
            if (repo != null) {
                if (themes.isEmpty()) themes = repo.themes()
                repo.poems(theme)
            } else {
                emptyList()
            }
        }.onSuccess { poems = it }.onFailure { error = it.readable() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Text(stringResource(R.string.po_intro), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        if (themes.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(theme == null, { theme = null }, { Text(stringResource(R.string.filter_all)) })
                themes.forEach { t -> FilterChip(theme == t.id, { theme = t.id }, { JaText(t.ja) }) }
            }
        }
        val list = poems
        when {
            error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ })
            missing -> Notice(stringResource(R.string.po_pack_missing))
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list.isEmpty() && theme == null -> Notice(stringResource(R.string.po_pack_missing))
            list.isEmpty() -> Notice(stringResource(R.string.po_no_poems))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { p ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenPoem(p.id) },
                        headlineContent = { JaText("${p.title}　${p.author}") },
                        supportingContent = {
                            Column {
                                JaText(p.firstLine, maxLines = 1)
                                if (p.titleEn.isNotBlank()) Text(p.titleEn, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                    )
                }
            }
        }
    }
}

/** One poem: the text (public domain, never badged), then vocabulary, paraphrase, gloss and note (AI until reviewed). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PoemScreen(id: String, onOpenEntry: (Long) -> Unit) {
    val graph = rememberGraph()
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var poem by remember(id) { mutableStateOf<PoemDetail?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var showRuby by remember { mutableStateOf(true) }
    LaunchedEffect(id, attempt) {
        error = null
        runCatching { graph.poetry()?.poem(id) }.onSuccess { poem = it }.onFailure { error = it.readable() }
        loaded = true
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val p = poem
        when {
            error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ })
            !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
            p == null -> Notice(stringResource(R.string.po_poem_missing))
            else -> {
                JaText(p.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                JaText(p.author, style = MaterialTheme.typography.titleSmall)
                if (p.titleEn.isNotBlank()) Text(p.titleEn, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p.ruby.isNotEmpty()) {
                        Switch(showRuby, { showRuby = it })
                        Text(stringResource(R.string.po_show_ruby), style = MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick = { scope.launch { runCatching { voices.say(p.body) } } }) { PlayLabel(stringResource(R.string.po_listen)) }
                }
                // The poem itself: line by line, stanzas separated by a blank line.
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        var offset = 0
                        p.body.split("\n").forEach { line ->
                            if (line.isBlank()) Spacer(Modifier.height(12.dp))
                            else RubyText(line, offset, p.ruby, showRuby, MaterialTheme.typography.bodyLarge)
                            offset += line.length + 1
                        }
                    }
                }
                if (p.vocabulary.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionTitle(stringResource(R.string.po_vocabulary))
                        if (p.isAiGenerated) AiBadge()
                    }
                    p.vocabulary.forEach { w -> VocabRow(w.word, w.reading, w.gloss, onLookup = { scope.launch { lookup(graph, w.word)?.let(onOpenEntry) } }) }
                }
                Commentary(stringResource(R.string.po_paraphrase), p.paraphrase, japanese = true, ai = p.isAiGenerated)
                Commentary(stringResource(R.string.po_gloss), p.gloss, japanese = false, ai = p.isAiGenerated)
                Commentary(stringResource(R.string.po_note), p.note, japanese = false, ai = p.isAiGenerated)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { p.themes.forEach { Tag(it) } }
                p.work?.let { SourceNotes(it) }
            }
        }
    }
}

/** First dictionary hit for a vocabulary word (display lookup only; the dictionary does the matching). */
private suspend fun lookup(graph: app.tsumugi.api.AppGraph, word: String): Long? =
    runCatching { graph.dictionary()?.search(word)?.hits?.firstOrNull()?.entry?.id }.getOrNull()

@Composable
private fun VocabRow(word: String, reading: String, gloss: String, onLookup: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onLookup),
        headlineContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                JaText(word, style = MaterialTheme.typography.titleMedium)
                if (reading.isNotBlank() && reading != word) JaText(reading, style = MaterialTheme.typography.bodySmall)
            }
        },
        supportingContent = { Text(gloss) },
    )
}

@Composable
private fun Commentary(title: String, text: String, japanese: Boolean, ai: Boolean) {
    if (text.isBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        if (ai) AiBadge()
    }
    if (japanese) JaText(text, style = MaterialTheme.typography.bodyLarge) else Text(text, style = MaterialTheme.typography.bodyMedium)
}

/** The Aozora Bunko work: author dates, orthography, the card link and the colophon (D-276). */
@Composable
internal fun SourceNotes(work: AozoraSource) {
    val context = LocalContext.current
    SectionTitle(stringResource(R.string.po_source))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JaText("${work.title}　${work.author}（${work.authorReading}）", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.po_source_author, work.authorEn, work.born, work.died), style = MaterialTheme.typography.bodySmall)
            if (work.orthography.isNotBlank()) JaText(work.orthography, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.po_public_domain), style = MaterialTheme.typography.bodySmall)
            if (work.colophon.isNotBlank()) JaText(work.colophon, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (work.cardUrl.isNotBlank()) {
                TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(work.cardUrl))) } }) {
                    Text(stringResource(R.string.po_open_card))
                }
            }
        }
    }
}
