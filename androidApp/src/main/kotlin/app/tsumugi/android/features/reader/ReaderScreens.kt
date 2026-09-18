package app.tsumugi.android.features.reader

import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.readable
import androidx.compose.material3.LinearProgressIndicator
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.localized
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.platform.Speech
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.dictionary.EntrySummary
import app.tsumugi.reader.AozoraWork
import app.tsumugi.reader.FeedItem
import app.tsumugi.reader.FuriganaMode
import app.tsumugi.reader.ReaderDocument
import app.tsumugi.reader.ReaderDocumentSummary
import app.tsumugi.reader.ReaderFeed
import app.tsumugi.reader.ReaderParagraph
import app.tsumugi.reader.ReaderSentence
import app.tsumugi.reader.ReaderToken
import kotlinx.coroutines.launch
import java.io.File

/** Reader library (BRIEF §5.8): paste, URL, EPUB, feeds and Aozora Bunko, with difficulty estimates. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderLibraryScreen(onOpen: (String) -> Unit, onFeeds: () -> Unit, onAozora: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<ReaderDocumentSummary>>(emptyList()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    // F-33: a failed import shows an error with a retry of the same import.
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    // F-26: analysis of a long import runs on IO and reports its progress here.
    val analysis by graph.reader.analysisProgress.collectAsStateWithLifecycle()
    suspend fun reload() {
        runCatching { graph.reader.documents() }
            .onSuccess { docs = it; listError = null }
            .onFailure { listError = it.readable() }
    }
    LaunchedEffect(Unit) { reload() }
    fun run(label: String, block: suspend () -> String) {
        status = context.getString(R.string.status_working, label)
        failure = null
        scope.launch {
            runCatching { block() }.onSuccess { id -> status = null; retry = null; reload(); onOpen(id) }
                .onFailure {
                    status = null
                    failure = context.getString(R.string.status_failed, label, it.readable())
                    retry = { run(label, block) }
                }
        }
    }
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run(context.getString(R.string.reader_importing_epub)) {
            val file = File(context.cacheDir, "import.epub")
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            graph.reader.importEpub(file.path)
        }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = "paste"; input = "" }) { Text(stringResource(R.string.reader_paste)) }
            OutlinedButton(onClick = { dialog = "url"; input = "" }) { Text("URL") }
            OutlinedButton(onClick = { epubPicker.launch(arrayOf("application/epub+zip", "*/*")) }) { Text("EPUB") }
            OutlinedButton(onClick = onFeeds) { Text(stringResource(R.string.title_feeds)) }
            OutlinedButton(onClick = onAozora) { Text(stringResource(R.string.title_aozora)) }
        }
        status?.let { Text(it, Modifier.padding(12.dp)) }
        analysis?.let { a ->
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.reader_analyzing, (a.fraction * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { a.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
        failure?.let { message ->
            ErrorState(message, onRetry = { retry?.invoke() }, modifier = Modifier.padding(horizontal = 12.dp))
        }
        listError?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { scope.launch { reload() } }, modifier = Modifier.padding(12.dp)) }
        if (docs.isEmpty()) Text(stringResource(R.string.reader_empty), Modifier.padding(16.dp))
        LazyColumn {
            items(docs, key = { it.id }) { d ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(d.id) },
                    headlineContent = { JaText(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 2) },
                    supportingContent = {
                        Text(listOfNotNull(d.levelLabel, d.knownRatio?.let { stringResource(R.string.reader_known, (it * 100).toInt()) }).joinToString(" · "))
                    },
                    trailingContent = { TextButton(onClick = { scope.launch { graph.reader.delete(d.id); reload() } }) { Text(stringResource(R.string.action_delete)) } },
                )
            }
        }
    }
    dialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (kind == "url") R.string.reader_url_title else R.string.reader_paste_title)) },
            text = {
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), minLines = if (kind == "url") 1 else 6,
                    textStyle = MaterialTheme.typography.bodyLarge.japanese())
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = input
                    dialog = null
                    if (kind == "url") run(context.getString(R.string.reader_fetching)) { graph.reader.importUrl(text) } else run(context.getString(R.string.reader_importing)) { graph.reader.importText(text) }
                }, enabled = input.isNotBlank()) { Text(stringResource(R.string.action_open)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The reader: furigana, tap a word for a non-blocking popup, long-press a sentence for grammar + audio. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(docId: String, onOpenEntry: (Long) -> Unit, onOpenGrammar: (String) -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    val speech = remember { Speech(context) }
    DisposableEffect(Unit) { onDispose { speech.shutdown() } }
    val speaking by speech.speaking.collectAsStateWithLifecycle()
    var doc by remember { mutableStateOf<ReaderDocument?>(null) }
    val paragraphs = remember { mutableStateListOf<ReaderParagraph>() }
    var ranges by remember { mutableStateOf<List<IntRange>>(emptyList()) }
    var loaded by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(FuriganaMode.UNKNOWN_ONLY) }
    var selected by remember { mutableStateOf<Pair<ReaderToken, ReaderSentence>?>(null) }
    var summary by remember { mutableStateOf<EntrySummary?>(null) }
    var sentencePanel by remember { mutableStateOf<ReaderSentence?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val lookUpLabel = stringResource(R.string.reader_look_up)
    val sentenceLabel = stringResource(R.string.reader_sentence_actions)

    suspend fun loadMore() {
        val d = doc ?: return
        val analyzer = graph.reader.analyzer() ?: return
        val next = ranges.drop(loaded).take(PAGE)
        next.forEach { paragraphs += analyzer.paragraph(d.body, it, d.ruby) }
        loaded += next.size
    }
    LaunchedEffect(docId) {
        doc = graph.reader.document(docId)
        val d = doc ?: return@LaunchedEffect
        ranges = graph.reader.analyzer()?.paragraphs(d.body).orEmpty()
        loadMore()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= paragraphs.size - 3 && loaded < ranges.size) loadMore()
            paragraphs.getOrNull(listState.firstVisibleItemIndex)?.let { graph.reader.setProgress(docId, it.start) }
        }
    }
    LaunchedEffect(selected) {
        summary = selected?.first?.entryId?.let { graph.dictionary()?.summaries(listOf(it))?.firstOrNull() }
        note = null
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.reader_furigana), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                listOf(
                    FuriganaMode.UNKNOWN_ONLY to R.string.reader_furigana_unknown,
                    FuriganaMode.ALL to R.string.reader_furigana_all,
                    FuriganaMode.NONE to R.string.reader_furigana_none,
                ).forEach { (m, label) ->
                    FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(stringResource(label)) })
                }
                TextButton(onClick = {
                    if (speaking != null) speech.stop() else paragraphs.getOrNull(listState.firstVisibleItemIndex)?.let { p ->
                        doc?.body?.let { speech.speak(it.substring(p.start, minOf(it.length, p.end + 2000)), startOffset = p.start) }
                    }
                }) { Text(stringResource(if (speaking != null) R.string.reader_stop else R.string.reader_read_aloud)) }
            }
            doc?.let { d -> JaText(d.title, Modifier.padding(horizontal = 16.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(paragraphs, key = { it.start }) { p ->
                    FlowRow {
                        p.sentences.forEach { s ->
                            s.tokens.forEach { t ->
                                val highlighted = speaking?.let { t.start in it } == true
                                val ruby = t.showFurigana(mode)
                                Column(
                                    Modifier
                                        .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(3.dp))
                                        .combinedClickable(
                                            onClickLabel = lookUpLabel,
                                            onLongClickLabel = sentenceLabel,
                                            onClick = { if (t.isWord) selected = t to s },
                                            onLongClick = { sentencePanel = s },
                                        )
                                        // TalkBack reads each word once, in Japanese: the surface, then its reading when furigana is shown.
                                        .clearAndSetSemantics {
                                            text = ja(if (ruby && !t.reading.isNullOrEmpty()) "${t.surface}（${t.reading}）" else t.surface)
                                        },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    // Sizes follow the body style so they scale with the system font size.
                                    Text(if (ruby) t.reading.orEmpty() else " ", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall.japanese())
                                    Text(
                                        t.surface,
                                        style = MaterialTheme.typography.titleLarge.japanese(),
                                        color = if (t.isWord && !t.known) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        selected?.let { (token, sentence) ->
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        JaText(summary?.headword ?: token.dictionaryForm ?: token.surface, style = MaterialTheme.typography.titleLarge)
                        JaText(summary?.reading ?: token.reading.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        token.stage?.let { Tag(it.localized()) }
                    }
                    if (token.deinflection.isNotEmpty()) Text("← " + token.deinflection.joinToString(" ← "), style = MaterialTheme.typography.labelSmall)
                    Text(summary?.glossPreview.orEmpty(), maxLines = 3)
                    note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.tertiary) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { note = context.getString(if (graph.reader.mine(token, sentence) != null) R.string.reader_mined else R.string.reader_mine_failed) } }) {
                            Text(stringResource(R.string.action_add_to_reviews))
                        }
                        OutlinedButton(onClick = { token.entryId?.let(onOpenEntry) }) { Text(stringResource(R.string.reader_details)) }
                        TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
        sentencePanel?.let { s ->
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    JaText(s.text, style = MaterialTheme.typography.bodyLarge)
                    if (s.grammarPointIds.isNotEmpty()) {
                        Text(stringResource(R.string.reader_grammar_here), style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.grammarPointIds.take(8).forEach { id -> TextButton(onClick = { onOpenGrammar(id) }) { Text(id.substringAfter('-').replace('-', ' ')) } }
                        }
                    }
                    Text(stringResource(R.string.reader_no_translation), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { speech.speak(s.text, startOffset = s.start) }) { Text(stringResource(R.string.reader_listen)) }
                        TextButton(onClick = { sentencePanel = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
    }
}

private const val PAGE = 20

@Composable
fun FeedsScreen(onOpenDoc: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var feeds by remember { mutableStateOf<List<ReaderFeed>>(emptyList()) }
    var items by remember { mutableStateOf<List<FeedItem>>(emptyList()) }
    var url by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { feeds = graph.reader.feeds.feeds() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.reader_feeds_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.reader_feed_url)) }, singleLine = true)
            Button(onClick = {
                scope.launch {
                    status = runCatching { val (_, list) = graph.reader.feeds.add(url.trim()); items = list; url = ""; feeds = graph.reader.feeds.feeds(); null }
                        .getOrElse { context.getString(R.string.reader_feed_failed, it.message.orEmpty()) }
                }
            }, enabled = url.isNotBlank()) { Text(stringResource(R.string.action_add)) }
        }
        status?.let { Text(it) }
        feeds.forEach { f ->
            Text(f.title, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { scope.launch { items = runCatching { graph.reader.feeds.items(f) }.getOrDefault(emptyList()) } }.padding(vertical = 12.dp),
                style = MaterialTheme.typography.titleSmall)
        }
        LazyColumn {
            items(items) { item ->
                ListItem(
                    modifier = Modifier.clickable { scope.launch { runCatching { graph.reader.importFeedItem(item) }.onSuccess(onOpenDoc) } },
                    headlineContent = { JaText(item.title, style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { item.published?.let { Text(it) } },
                )
            }
        }
    }
}

@Composable
fun AozoraScreen(onOpenDoc: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var works by remember { mutableStateOf<List<AozoraWork>?>(null) }
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.reader_aozora_hint), style = MaterialTheme.typography.bodySmall)
        if (works == null) {
            Button(onClick = { status = context.getString(R.string.reader_aozora_downloading); scope.launch { works = runCatching { graph.reader.aozora.catalogue() }.getOrNull(); status = if (works == null) context.getString(R.string.reader_aozora_failed) else null } }) {
                Text(stringResource(R.string.reader_aozora_load))
            }
        }
        status?.let { Text(it) }
        works?.let { list ->
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.reader_aozora_search)) }, singleLine = true)
            val shown = list.filter { query.isBlank() || query in it.title || query in it.author }.take(200)
            LazyColumn {
                items(shown, key = { it.id }) { w ->
                    ListItem(
                        modifier = Modifier.clickable { status = context.getString(R.string.status_working, context.getString(R.string.reader_importing)); scope.launch { runCatching { graph.reader.importAozora(w) }.onSuccess { status = null; onOpenDoc(it) }.onFailure { status = context.getString(R.string.status_failed, context.getString(R.string.reader_importing), it.message.orEmpty()) } } },
                        headlineContent = { JaText(w.title, style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = { JaText(w.author, style = MaterialTheme.typography.bodyMedium) },
                    )
                }
            }
        }
    }
}
