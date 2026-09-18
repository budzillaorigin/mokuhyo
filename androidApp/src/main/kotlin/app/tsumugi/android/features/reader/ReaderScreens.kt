package app.tsumugi.android.features.reader

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
@Composable
fun ReaderLibraryScreen(onOpen: (String) -> Unit, onFeeds: () -> Unit, onAozora: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<ReaderDocumentSummary>>(emptyList()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    suspend fun reload() { docs = graph.reader.documents() }
    LaunchedEffect(Unit) { reload() }
    fun run(label: String, block: suspend () -> String) {
        status = "$label…"
        scope.launch {
            runCatching { block() }.onSuccess { id -> status = null; reload(); onOpen(id) }
                .onFailure { status = "$label failed: ${it.message}" }
        }
    }
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        run("Importing EPUB") {
            val file = File(context.cacheDir, "import.epub")
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
            graph.reader.importEpub(file.path)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = "paste"; input = "" }) { Text("Paste") }
            OutlinedButton(onClick = { dialog = "url"; input = "" }) { Text("URL") }
            OutlinedButton(onClick = { epubPicker.launch(arrayOf("application/epub+zip", "*/*")) }) { Text("EPUB") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFeeds) { Text("Feeds") }
            OutlinedButton(onClick = onAozora) { Text("Aozora Bunko") }
        }
        status?.let { Text(it, Modifier.padding(12.dp)) }
        if (docs.isEmpty()) Text("Paste text, add a web article or EPUB, or pick a public-domain book from Aozora Bunko.", Modifier.padding(16.dp))
        LazyColumn {
            items(docs, key = { it.id }) { d ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(d.id) },
                    headlineContent = { Text(d.title, style = MaterialTheme.typography.titleMedium.japanese(), maxLines = 2) },
                    supportingContent = {
                        Text(listOfNotNull(d.levelLabel, d.knownRatio?.let { "${(it * 100).toInt()}% known" }).joinToString(" · "))
                    },
                    trailingContent = { TextButton(onClick = { scope.launch { graph.reader.delete(d.id); reload() } }) { Text("Delete") } },
                )
            }
        }
    }
    dialog?.let { kind ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(if (kind == "url") "Read a web page" else "Paste Japanese text") },
            text = {
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), minLines = if (kind == "url") 1 else 6,
                    textStyle = MaterialTheme.typography.bodyLarge.japanese())
            },
            confirmButton = {
                TextButton(onClick = {
                    val text = input
                    dialog = null
                    if (kind == "url") run("Fetching") { graph.reader.importUrl(text) } else run("Importing") { graph.reader.importText(text) }
                }, enabled = input.isNotBlank()) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
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

    suspend fun loadMore() {
        val d = doc ?: return
        val analyzer = graph.reader.analyzer() ?: return
        val next = ranges.drop(loaded).take(PAGE)
        next.forEach { paragraphs += analyzer.paragraph(d.body, it) }
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
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(FuriganaMode.UNKNOWN_ONLY to "Unknown", FuriganaMode.ALL to "All", FuriganaMode.NONE to "None").forEach { (m, label) ->
                    FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(label) })
                }
                TextButton(onClick = {
                    if (speaking != null) speech.stop() else paragraphs.getOrNull(listState.firstVisibleItemIndex)?.let { p ->
                        doc?.body?.let { speech.speak(it.substring(p.start, minOf(it.length, p.end + 2000)), startOffset = p.start) }
                    }
                }) { Text(if (speaking != null) "Stop" else "Read aloud") }
            }
            doc?.let { d -> Text(d.title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge.japanese()) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(paragraphs, key = { it.start }) { p ->
                    FlowRow {
                        p.sentences.forEach { s ->
                            s.tokens.forEach { t ->
                                val highlighted = speaking?.let { t.start in it } == true
                                Column(
                                    Modifier
                                        .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(3.dp))
                                        .combinedClickable(
                                            onClick = { if (t.isWord) selected = t to s },
                                            onLongClick = { sentencePanel = s },
                                        ),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    val ruby = t.showFurigana(mode)
                                    Text(if (ruby) t.reading.orEmpty() else " ", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall.japanese())
                                    Text(
                                        t.surface,
                                        style = MaterialTheme.typography.bodyLarge.japanese().copy(fontSize = 20.sp),
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
                        Text(summary?.headword ?: token.dictionaryForm ?: token.surface, style = MaterialTheme.typography.titleLarge.japanese())
                        Text(summary?.reading ?: token.reading.orEmpty(), style = MaterialTheme.typography.bodyMedium.japanese())
                        token.stage?.let { Tag(it.label) }
                    }
                    if (token.deinflection.isNotEmpty()) Text("← " + token.deinflection.joinToString(" ← "), style = MaterialTheme.typography.labelSmall)
                    Text(summary?.glossPreview.orEmpty(), maxLines = 3)
                    note?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { note = if (graph.reader.mine(token, sentence) != null) "Added with this sentence as context." else "Couldn't add." } }) { Text("Add to reviews") }
                        OutlinedButton(onClick = { token.entryId?.let(onOpenEntry) }) { Text("Details") }
                        TextButton(onClick = { selected = null }) { Text("Close") }
                    }
                }
            }
        }
        sentencePanel?.let { s ->
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(s.text, style = MaterialTheme.typography.bodyLarge.japanese())
                    if (s.grammarPointIds.isNotEmpty()) {
                        Text("Grammar in this sentence", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            s.grammarPointIds.take(8).forEach { id -> TextButton(onClick = { onOpenGrammar(id) }) { Text(id.substringAfter('-').replace('-', ' ')) } }
                        }
                    }
                    Text("Translation arrives with the on-device AI (Phase 6).", style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { speech.speak(s.text, startOffset = s.start) }) { Text("Listen") }
                        TextButton(onClick = { sentencePanel = null }) { Text("Close") }
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
    LaunchedEffect(Unit) { feeds = graph.reader.feeds.feeds() }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add any RSS/Atom feed (e.g. NHK News Web Easy). Articles are fetched to this device only.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.weight(1f), label = { Text("Feed URL") }, singleLine = true)
            Button(onClick = {
                scope.launch {
                    status = runCatching { val (_, list) = graph.reader.feeds.add(url.trim()); items = list; url = ""; feeds = graph.reader.feeds.feeds(); null }
                        .getOrElse { "Couldn't add: ${it.message}" }
                }
            }, enabled = url.isNotBlank()) { Text("Add") }
        }
        status?.let { Text(it) }
        feeds.forEach { f ->
            Text(f.title, Modifier.fillMaxWidth().clickable { scope.launch { items = runCatching { graph.reader.feeds.items(f) }.getOrDefault(emptyList()) } }.padding(vertical = 6.dp),
                style = MaterialTheme.typography.titleSmall)
        }
        LazyColumn {
            items(items) { item ->
                ListItem(
                    modifier = Modifier.clickable { scope.launch { runCatching { graph.reader.importFeedItem(item) }.onSuccess(onOpenDoc) } },
                    headlineContent = { Text(item.title, style = MaterialTheme.typography.bodyLarge.japanese()) },
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
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Public-domain Japanese literature from Aozora Bunko. The catalogue downloads once when you open this.", style = MaterialTheme.typography.bodySmall)
        if (works == null) {
            Button(onClick = { status = "Downloading catalogue…"; scope.launch { works = runCatching { graph.reader.aozora.catalogue() }.getOrNull(); status = if (works == null) "Couldn't download the catalogue." else null } }) {
                Text("Load catalogue")
            }
        }
        status?.let { Text(it) }
        works?.let { list ->
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Title or author") }, singleLine = true)
            val shown = list.filter { query.isBlank() || query in it.title || query in it.author }.take(200)
            LazyColumn {
                items(shown, key = { it.id }) { w ->
                    ListItem(
                        modifier = Modifier.clickable { status = "Importing…"; scope.launch { runCatching { graph.reader.importAozora(w) }.onSuccess { status = null; onOpenDoc(it) }.onFailure { status = "Import failed: ${it.message}" } } },
                        headlineContent = { Text(w.title, style = MaterialTheme.typography.bodyLarge.japanese()) },
                        supportingContent = { Text(w.author, style = MaterialTheme.typography.bodyMedium.japanese()) },
                    )
                }
            }
        }
    }
}
