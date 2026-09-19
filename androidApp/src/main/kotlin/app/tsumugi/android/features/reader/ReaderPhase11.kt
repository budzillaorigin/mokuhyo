package app.tsumugi.android.features.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.features.decks.CoverageOverlay
import app.tsumugi.android.features.decks.DeckSource
import app.tsumugi.android.features.decks.DifficultyBadge
import app.tsumugi.android.features.decks.MarkKnownButton
import app.tsumugi.android.features.decks.coverageSummary
import app.tsumugi.android.features.immersion.ImmersionTracker
import app.tsumugi.android.platform.Ocr
import app.tsumugi.android.platform.Speech
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.localized
import app.tsumugi.android.ui.readable
import app.tsumugi.android.ui.rememberBitmap
import app.tsumugi.coverage.LibraryCoverage
import app.tsumugi.coverage.OneTargetSentence
import app.tsumugi.dictionary.EntrySummary
import app.tsumugi.immersion.ImmersionMode
import app.tsumugi.immersion.ImmersionOrigin
import app.tsumugi.reader.AnnotationKind
import app.tsumugi.reader.ContextCardResult
import app.tsumugi.reader.ContextDrill
import app.tsumugi.reader.DocumentWord
import app.tsumugi.reader.FuriganaMode
import app.tsumugi.reader.LearnerLevel
import app.tsumugi.reader.PageImageFile
import app.tsumugi.reader.ReaderAnnotation
import app.tsumugi.reader.ReaderDocument
import app.tsumugi.reader.ReaderDocumentSummary
import app.tsumugi.reader.ReaderParagraph
import app.tsumugi.reader.ReaderSentence
import app.tsumugi.reader.ReaderToken
import app.tsumugi.reader.ScreenshotPage
import app.tsumugi.reader.TokenPitch
import app.tsumugi.reader.showFurigana
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reader library (BRIEF §5.8, BRIEF_V2 §6.1/§6.4): paste, URL, EPUB, screenshots, feeds and Aozora Bunko, with
 * each document's coverage and §6.4 difficulty badge, and a sort by coverage. Unprofiled documents are profiled in
 * the background with progress and a cancel (rule 15).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderLibraryScreen(onOpen: (String) -> Unit, onFeeds: () -> Unit, onAozora: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf<List<ReaderDocumentSummary>>(emptyList()) }
    var coverage by remember { mutableStateOf<Map<String, LibraryCoverage>>(emptyMap()) }
    var sortByCoverage by remember { mutableStateOf(false) }
    var coverageOrder by remember { mutableStateOf<List<String>>(emptyList()) }
    var profiling by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var profileJob by remember { mutableStateOf<Job?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf<(() -> Unit)?>(null) }
    var listError by remember { mutableStateOf<String?>(null) }
    var ocrJob by remember { mutableStateOf<Job?>(null) }
    val analysis by graph.reader.analysisProgress.collectAsStateWithLifecycle()
    suspend fun reloadCoverage() {
        runCatching { graph.coverage.librarySortedByCoverage() }.onSuccess { rows ->
            coverage = rows.associateBy { it.document.id }
            coverageOrder = rows.map { it.document.id }
        }
    }
    suspend fun reload() {
        runCatching { graph.reader.documents() }
            .onSuccess { docs = it; listError = null }
            .onFailure { listError = it.readable() }
        reloadCoverage()
    }
    fun profile() {
        profileJob?.cancel()
        profileJob = scope.launch {
            try {
                graph.coverage.profileLibrary { done, total -> profiling = done to total }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Documents that can't be profiled (no dictionary pack) keep "not analyzed yet".
            } finally {
                profiling = null
                profileJob = null
                reloadCoverage()
            }
        }
    }
    LaunchedEffect(Unit) {
        reload()
        if (docs.any { it.id !in coverage || coverage[it.id]?.coverage == null }) profile()
    }
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
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } } }
            graph.reader.importEpub(file.path)
        }
    }
    // §6.4 screenshot import: the Photo Picker (no storage permission) → on-device ML Kit OCR → one document whose
    // pages keep their pictures.
    val screenshotPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        failure = null
        ocrJob = scope.launch {
            try {
                val pages = ArrayList<ScreenshotPage>()
                uris.forEachIndexed { i, uri ->
                    status = context.getString(R.string.screenshots_reading, i + 1, uris.size)
                    val text = Ocr.recognize(context, uri).joinToString("\n") { it.text }
                    val ext = if (context.contentResolver.getType(uri)?.contains("png") == true) "png" else "jpg"
                    val pending = graph.reader.screenshots.screenshotFile(ext)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { input -> File(pending.path).outputStream().use { input.copyTo(it) } }
                            ?: error(context.getString(R.string.screenshots_cant_open))
                    }
                    pages += ScreenshotPage(pending, text)
                }
                if (pages.all { it.ocrText.isBlank() }) error(context.getString(R.string.screenshots_no_text))
                val id = graph.reader.importScreenshots(pages)
                status = null
                reload()
                onOpen(id)
            } catch (e: CancellationException) {
                status = null
                throw e
            } catch (e: Exception) {
                status = null
                failure = context.getString(R.string.screenshots_failed, e.readable())
                retry = null
            } finally {
                ocrJob = null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = "paste"; input = "" }) { Text(stringResource(R.string.reader_paste)) }
            OutlinedButton(onClick = { dialog = "url"; input = "" }) { Text("URL") }
            OutlinedButton(onClick = { epubPicker.launch(arrayOf("application/epub+zip", "*/*")) }) { Text("EPUB") }
            OutlinedButton(onClick = { screenshotPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = ocrJob == null) {
                Text(stringResource(R.string.screenshots_import))
            }
            OutlinedButton(onClick = onFeeds) { Text(stringResource(R.string.title_feeds)) }
            OutlinedButton(onClick = onAozora) { Text(stringResource(R.string.title_aozora)) }
        }
        status?.let {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(it, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                if (ocrJob != null) TextButton(onClick = { ocrJob?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        analysis?.let { a ->
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.reader_analyzing, (a.fraction * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { a.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
            }
        }
        profiling?.let { (done, total) ->
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.library_profiling, done, total), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { profileJob?.cancel() }) { Text(stringResource(R.string.action_cancel)) }
            }
        }
        failure?.let { message ->
            ErrorState(message, onRetry = { retry?.invoke() ?: run { failure = null } }, modifier = Modifier.padding(horizontal = 12.dp))
        }
        listError?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { scope.launch { reload() } }, modifier = Modifier.padding(12.dp)) }
        if (docs.isEmpty()) Text(stringResource(R.string.reader_empty_v2), Modifier.padding(16.dp))
        if (docs.size > 1) {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!sortByCoverage, { sortByCoverage = false }, { Text(stringResource(R.string.sort_recent)) })
                FilterChip(sortByCoverage, { sortByCoverage = true }, { Text(stringResource(R.string.sort_coverage)) })
                if (profiling == null && docs.any { coverage[it.id]?.coverage == null }) TextButton(onClick = ::profile) { Text(stringResource(R.string.library_profile)) }
            }
        }
        val shown = if (sortByCoverage) {
            val byId = docs.associateBy { it.id }
            coverageOrder.mapNotNull { byId[it] } + docs.filter { it.id !in coverageOrder }
        } else docs
        LazyColumn {
            items(shown, key = { it.id }) { d ->
                val c = coverage[d.id]
                ListItem(
                    modifier = Modifier.clickable { onOpen(d.id) },
                    headlineContent = { JaText(d.title, style = MaterialTheme.typography.titleMedium, maxLines = 2) },
                    supportingContent = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            val cov = c?.coverage
                            if (cov != null) Text(coverageSummary(cov), style = MaterialTheme.typography.bodySmall)
                            else Text(listOfNotNull(d.levelLabel, d.knownRatio?.let { stringResource(R.string.reader_known, (it * 100).toInt()) }).joinToString(" · "))
                            c?.difficulty?.let { DifficultyBadge(it) }
                        }
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

private enum class ReaderTab(val label: Int) {
    READ(R.string.reader_tab_read),
    WORDS(R.string.reader_tab_words),
    ONE_T(R.string.reader_tab_onet),
    NOTES(R.string.reader_tab_notes),
}

/** Items before the paragraphs in the reading list (the coverage overlay and page pictures). */
private const val HEADER = 1

/** Annotation styling: a highlight colour when none was chosen. */
private val HIGHLIGHT = Color(0x66FFD54F)

/**
 * The reader: furigana, tap a word for a non-blocking popup, long-press a sentence for grammar + audio. Phase 11
 * adds the coverage overlay, 1T sentences highlighted with "Mine", annotations over a selection, the document's
 * word list with a context drill, screenshot pages, "Mark known", and automatic immersion logging.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    docId: String,
    onOpenEntry: (Long) -> Unit,
    onOpenGrammar: (String) -> Unit,
    onOpenAiSettings: () -> Unit = {},
    onCreateDeck: (DeckSource) -> Unit = {},
) {
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
    var aboveLevel by remember { mutableStateOf(true) }
    var level by remember { mutableStateOf(LearnerLevel.UNKNOWN) }
    var pitchOn by remember { mutableStateOf(false) }
    val pitch = remember { mutableStateMapOf<Int, List<TokenPitch>>() }
    var questionsOpen by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<ReaderToken, ReaderSentence>?>(null) }
    var summary by remember { mutableStateOf<EntrySummary?>(null) }
    var sentencePanel by remember { mutableStateOf<ReaderSentence?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(ReaderTab.READ) }
    // Annotations (§6.4): a selection made by tapping its first and last word in annotate mode.
    var annotating by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<IntRange?>(null) }
    var annotations by remember { mutableStateOf<List<ReaderAnnotation>>(emptyList()) }
    var annotationVersion by remember { mutableIntStateOf(0) }
    // 1T sentences (§6.11), keyed by their start offset in the body.
    var oneT by remember { mutableStateOf<List<OneTargetSentence>>(emptyList()) }
    var oneTProgress by remember { mutableStateOf<Double?>(null) }
    var oneTError by remember { mutableStateOf<String?>(null) }
    var oneTAttempt by remember { mutableIntStateOf(0) }
    var pages by remember { mutableStateOf<List<PageImageFile>>(emptyList()) }
    var bigPage by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val lookUpLabel = stringResource(R.string.reader_look_up)
    val sentenceLabel = stringResource(R.string.reader_sentence_actions)

    ImmersionTracker(ImmersionOrigin.READER, ImmersionMode.ACTIVE, docId, doc?.title)

    suspend fun loadMore() {
        val d = doc ?: return
        val analyzer = graph.reader.analyzer() ?: return
        val next = ranges.drop(loaded).take(PAGE)
        next.forEach { paragraphs += analyzer.paragraph(d.body, it, d.ruby) }
        loaded += next.size
    }
    LaunchedEffect(docId) {
        doc = graph.reader.document(docId)
        level = runCatching { graph.reader.learnerLevel() }.getOrDefault(LearnerLevel.UNKNOWN)
        val d = doc ?: return@LaunchedEffect
        pages = runCatching { graph.reader.screenshots.pageImages(docId) }.getOrDefault(emptyList())
        ranges = graph.reader.analyzer()?.paragraphs(d.body).orEmpty()
        loadMore()
    }
    LaunchedEffect(doc, annotationVersion) {
        val d = doc ?: return@LaunchedEffect
        annotations = runCatching { graph.reader.annotations.forDocument(d) }.getOrDefault(emptyList())
    }
    LaunchedEffect(docId, oneTAttempt) {
        oneTProgress = 0.0
        oneTError = null
        runCatching { graph.coverage.oneTargetSentences(docId, limit = 200) { oneTProgress = it } }
            .onSuccess { oneT = it }
            .onFailure { oneTError = it.readable() }
        oneTProgress = null
    }
    val oneTByStart = remember(oneT) { oneT.associateBy { it.start } }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last - HEADER >= paragraphs.size - 3 && loaded < ranges.size) loadMore()
            paragraphs.getOrNull(listState.firstVisibleItemIndex - HEADER)?.let { graph.reader.setProgress(docId, it.start) }
        }
    }
    LaunchedEffect(selected) {
        val sel = selected
        summary = sel?.first?.entryId?.let { graph.dictionary()?.summaries(listOf(it))?.firstOrNull() }
        note = null
        // §6.4 auto vocabulary list: every looked-up word joins the document's list.
        if (sel != null && sel.first.isWord) runCatching { graph.reader.vocabulary.recordLookup(docId, sel.first, sel.second, summary?.glossPreview.orEmpty()) }
    }
    fun jumpTo(offset: Int) {
        tab = ReaderTab.READ
        scope.launch {
            while (paragraphs.none { offset in it.start..it.end } && loaded < ranges.size) loadMore()
            val i = paragraphs.indexOfFirst { offset in it.start..it.end }
            if (i >= 0) listState.scrollToItem(i + HEADER)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                ReaderTab.entries.forEach { t ->
                    val label = if (t == ReaderTab.ONE_T && oneT.isNotEmpty()) "${stringResource(t.label)} (${oneT.size})" else stringResource(t.label)
                    Tab(selected = t == tab, onClick = { tab = t }, text = { Text(label) })
                }
            }
            when (tab) {
                ReaderTab.READ -> {
                    FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.reader_furigana), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelLarge)
                        FilterChip(selected = aboveLevel, onClick = { aboveLevel = true }, label = { Text(stringResource(R.string.reader_furigana_level)) })
                        listOf(
                            FuriganaMode.UNKNOWN_ONLY to R.string.reader_furigana_unknown,
                            FuriganaMode.ALL to R.string.reader_furigana_all,
                            FuriganaMode.NONE to R.string.reader_furigana_none,
                        ).forEach { (m, label) ->
                            FilterChip(selected = !aboveLevel && mode == m, onClick = { aboveLevel = false; mode = m }, label = { Text(stringResource(label)) })
                        }
                        FilterChip(selected = pitchOn, onClick = { pitchOn = !pitchOn }, label = { Text(stringResource(R.string.reader_pitch)) })
                        FilterChip(selected = annotating, onClick = { annotating = !annotating; selection = null; selected = null }, label = { Text(stringResource(R.string.annotate_mode)) })
                        TextButton(onClick = { questionsOpen = true }) { Text(stringResource(R.string.reader_questions)) }
                        TextButton(onClick = {
                            if (speaking != null) speech.stop() else paragraphs.getOrNull(listState.firstVisibleItemIndex - HEADER)?.let { p ->
                                doc?.body?.let { speech.speak(it.substring(p.start, minOf(it.length, p.end + 2000)), startOffset = p.start) }
                            }
                        }) { Text(stringResource(if (speaking != null) R.string.reader_stop else R.string.reader_read_aloud)) }
                    }
                    if (annotating) Text(stringResource(R.string.annotate_hint), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    doc?.let { d -> JaText(d.title, Modifier.padding(horizontal = 16.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item(key = "header") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                CoverageOverlay(
                                    key = docId,
                                    load = { p -> graph.coverage.documentCoverage(docId, p) },
                                    onCreateDeck = { onCreateDeck(DeckSource.Document(docId)) },
                                )
                                if (pages.isNotEmpty()) PageStrip(pages, onOpen = { bigPage = it })
                            }
                        }
                        items(paragraphs, key = { it.start }) { p ->
                            if (pitchOn) {
                                LaunchedEffect(p.start) {
                                    p.sentences.forEach { s -> if (s.start !in pitch) pitch[s.start] = runCatching { graph.reader.pitch(s) }.getOrDefault(emptyList()) }
                                }
                            }
                            FlowRow {
                                p.sentences.forEach { s ->
                                    val oneTarget = oneTByStart[s.start]
                                    s.tokens.forEach { t ->
                                        val highlighted = speaking?.let { t.start in it } == true
                                        val ruby = if (aboveLevel) t.showFurigana(FuriganaMode.UNKNOWN_ONLY, level) else t.showFurigana(mode)
                                        val inSelection = selection?.let { t.start in it } == true
                                        val marks = annotations.filter { !it.detached && t.start >= it.start && t.start < it.end }
                                        val isTarget = oneTarget != null && t.start - s.start - (s.text.length - s.text.trimStart().length) == oneTarget.targetStart
                                        val underline = MaterialTheme.colorScheme.secondary
                                        val tertiary = MaterialTheme.colorScheme.tertiary
                                        Column(
                                            Modifier
                                                .background(
                                                    when {
                                                        inSelection -> MaterialTheme.colorScheme.primaryContainer
                                                        highlighted -> MaterialTheme.colorScheme.secondaryContainer
                                                        marks.any { it.kind == AnnotationKind.HIGHLIGHT } -> HIGHLIGHT
                                                        oneTarget != null -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                                                        else -> Color.Transparent
                                                    },
                                                    RoundedCornerShape(3.dp),
                                                )
                                                .then(if (marks.any { it.kind == AnnotationKind.BOX }) Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(2.dp)) else Modifier)
                                                .drawBehind {
                                                    val y = size.height - 1.5.dp.toPx()
                                                    if (marks.any { it.kind == AnnotationKind.GRAMMAR || it.kind == AnnotationKind.NOTE }) {
                                                        drawLine(underline, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                                                    }
                                                    if (isTarget) drawLine(tertiary, Offset(0f, y - 3.dp.toPx()), Offset(size.width, y - 3.dp.toPx()), strokeWidth = 2.dp.toPx())
                                                }
                                                .combinedClickable(
                                                    onClickLabel = lookUpLabel,
                                                    onLongClickLabel = sentenceLabel,
                                                    onClick = {
                                                        if (annotating) {
                                                            val tokenRange = t.start until t.end
                                                            selection = when (val cur = selection) {
                                                                null -> tokenRange
                                                                else -> minOf(cur.first, tokenRange.first)..maxOf(cur.last, tokenRange.last)
                                                            }
                                                        } else if (t.isWord) selected = t to s
                                                    },
                                                    onLongClick = { if (!annotating) sentencePanel = s },
                                                )
                                                .clearAndSetSemantics {
                                                    text = ja(if (ruby && !t.reading.isNullOrEmpty()) "${t.surface}（${t.reading}）" else t.surface)
                                                },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            if (pitchOn) PitchMarks(pitch[s.start]?.firstOrNull { it.start == t.start })
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
                ReaderTab.WORDS -> WordsTab(docId, onOpenEntry)
                ReaderTab.ONE_T -> OneTargetTab(oneT, oneTProgress, oneTError, onRetry = { oneTAttempt++ }, onJump = ::jumpTo)
                ReaderTab.NOTES -> NotesTab(annotations, onChanged = { annotationVersion++ }, onJump = ::jumpTo, onOpenGrammar = onOpenGrammar)
            }
        }
        val d = doc
        val sel = selection
        if (tab == ReaderTab.READ && annotating && sel != null && d != null) {
            AnnotationEditor(
                doc = d,
                range = sel,
                grammarIds = paragraphs.flatMap { it.sentences }.filter { it.start <= sel.last && it.end > sel.first }.flatMap { it.grammarPointIds }.distinct(),
                onDone = { selection = null; annotationVersion++ },
                onClear = { selection = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
                        token.entryId?.let { MarkKnownButton(it) }
                        OutlinedButton(onClick = { token.entryId?.let(onOpenEntry) }) { Text(stringResource(R.string.reader_details)) }
                        TextButton(onClick = { selected = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
        sentencePanel?.let { s ->
            val target = oneTByStart[s.start]
            Card(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp)) {
                // §6.16: the panel scrolls, since constructions and an inline exercise can outgrow the screen.
                Column(
                    Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (target != null) OneTargetMine(target)
                    // The sentence with its grammar constructions underlined, explained and practicable (§6.16).
                    SentenceGrammar(s, onOpenGrammar)
                    Text(stringResource(R.string.reader_no_translation), style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { speech.speak(s.text, startOffset = s.start) }) { Text(stringResource(R.string.reader_listen)) }
                        TextButton(onClick = { sentencePanel = null }) { Text(stringResource(R.string.action_close)) }
                    }
                }
            }
        }
        if (questionsOpen) doc?.let { dd -> QuestionsSheet(dd, level, onClose = { questionsOpen = false }, onOpenAiSettings = onOpenAiSettings) }
        bigPage?.let { path ->
            AlertDialog(
                onDismissRequest = { bigPage = null },
                text = { rememberBitmap(path)?.let { Image(it.asImageBitmap(), contentDescription = stringResource(R.string.screenshots_page)) } },
                confirmButton = { TextButton(onClick = { bigPage = null }) { Text(stringResource(R.string.action_close)) } },
            )
        }
    }
}

private const val PAGE = 20

/** The screenshot pages of a document (§6.4): thumbnails; tap to see the picture. */
@Composable
private fun PageStrip(pages: List<PageImageFile>, onOpen: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pages.forEachIndexed { i, p ->
            val path = p.path
            if (path == null) {
                // The picture hasn't synced to this device (recordings sync, D-111): say so instead of a blank.
                Text(stringResource(R.string.screenshots_missing, i + 1), style = MaterialTheme.typography.labelSmall)
            } else {
                val bitmap = rememberBitmap(path, maxPx = 240)
                bitmap?.let {
                    Image(
                        it.asImageBitmap(), contentDescription = stringResource(R.string.screenshots_page_n, i + 1),
                        modifier = Modifier.height(96.dp).clickable { onOpen(path) },
                    )
                }
            }
        }
    }
}

/** "Mine" for a 1T sentence: the target word goes to reviews with the sentence as context. */
@Composable
private fun OneTargetMine(s: OneTargetSentence) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember(s) { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.onet_line, s.target), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        Button(onClick = {
            scope.launch {
                note = runCatching { graph.coverage.mineOneTarget(s) }.fold(
                    { if (it != null) context.getString(R.string.reader_mined) else context.getString(R.string.reader_mine_failed) },
                    { it.readable() },
                )
            }
        }, enabled = note == null) { Text(stringResource(R.string.onet_mine)) }
    }
    note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
}

/** 1T sentences, best first (§6.11), each with "Mine" and a jump to where it is. */
@Composable
private fun OneTargetTab(list: List<OneTargetSentence>, progress: Double?, error: String?, onRetry: () -> Unit, onJump: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.onet_intro), style = MaterialTheme.typography.bodySmall)
        progress?.let { p ->
            Text(stringResource(R.string.onet_finding, (p * 100).toInt()), style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { p.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        error?.let { ErrorState(stringResource(R.string.onet_failed, it), onRetry = onRetry) }
        if (progress == null && error == null && list.isEmpty()) Text(stringResource(R.string.onet_none), style = MaterialTheme.typography.bodyMedium)
        list.forEach { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        ja(buildAnnotatedString {
                            val a = s.targetStart.coerceIn(0, s.text.length)
                            val b = s.targetEnd.coerceIn(a, s.text.length)
                            append(s.text.substring(0, a))
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)) { append(s.text.substring(a, b)) }
                            append(s.text.substring(b))
                        }),
                        style = MaterialTheme.typography.bodyLarge.japanese(),
                    )
                    Text(stringResource(R.string.onet_detail, s.targetLemma, s.targetReading.orEmpty(), s.occurrences), style = MaterialTheme.typography.labelSmall)
                    OneTargetMine(s)
                    TextButton(onClick = { onJump(s.start) }) { Text(stringResource(R.string.onet_show)) }
                }
            }
        }
    }
}

/** Kinds for the annotation tools. */
@Composable
private fun kindLabel(kind: AnnotationKind): String = stringResource(
    when (kind) {
        AnnotationKind.HIGHLIGHT -> R.string.annotate_highlight
        AnnotationKind.BOX -> R.string.annotate_box
        AnnotationKind.NOTE -> R.string.annotate_note
        AnnotationKind.GRAMMAR -> R.string.annotate_grammar
    },
)

/** The selection's tools: highlight, box, note, grammar span (with a detected grammar point), then save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnnotationEditor(doc: ReaderDocument, range: IntRange, grammarIds: List<String>, onDone: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var kind by remember { mutableStateOf(AnnotationKind.HIGHLIGHT) }
    var text by remember { mutableStateOf("") }
    var grammar by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val start = range.first.coerceIn(0, doc.body.length)
    val end = (range.last + 1).coerceIn(start, doc.body.length)
    Card(modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            JaText("「${doc.body.substring(start, end)}」", style = MaterialTheme.typography.bodyLarge, maxLines = 3)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnnotationKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, { Text(kindLabel(k)) }) }
            }
            if (kind == AnnotationKind.NOTE || kind == AnnotationKind.GRAMMAR) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.annotate_note_text)) })
            }
            if (kind == AnnotationKind.GRAMMAR) {
                if (grammarIds.isEmpty()) Text(stringResource(R.string.annotate_no_grammar), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    grammarIds.take(10).forEach { id -> FilterChip(grammar == id, { grammar = if (grammar == id) null else id }, { Text(id.substringAfter('-').replace('-', ' ')) }) }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        runCatching { graph.reader.annotations.add(doc, kind, start, end, text, null, if (kind == AnnotationKind.GRAMMAR) grammar else null) }
                            .onSuccess { onDone() }.onFailure { error = it.readable() }
                    }
                }, enabled = end > start && (kind != AnnotationKind.NOTE || text.isNotBlank())) { Text(stringResource(R.string.action_save)) }
                TextButton(onClick = onClear) { Text(stringResource(R.string.annotate_clear)) }
            }
        }
    }
}

/** Every annotation of the document, including ones whose text is gone (detached), with edit and delete. */
@Composable
private fun NotesTab(annotations: List<ReaderAnnotation>, onChanged: () -> Unit, onJump: (Int) -> Unit, onOpenGrammar: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<ReaderAnnotation?>(null) }
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.annotate_intro), style = MaterialTheme.typography.bodySmall)
        if (annotations.isEmpty()) Text(stringResource(R.string.annotate_none), style = MaterialTheme.typography.bodyMedium)
        annotations.sortedBy { it.start }.forEach { a ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(kindLabel(a.kind) + if (a.detached) " · " + stringResource(R.string.annotate_detached) else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    JaText("「${a.quote}」", style = MaterialTheme.typography.bodyLarge)
                    if (a.note.isNotBlank()) Text(a.note, style = MaterialTheme.typography.bodyMedium)
                    a.grammarPointId?.let { g -> TextButton(onClick = { onOpenGrammar(g) }) { Text(g.substringAfter('-').replace('-', ' ')) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (!a.detached) TextButton(onClick = { onJump(a.start) }) { Text(stringResource(R.string.onet_show)) }
                        TextButton(onClick = { editing = a; draft = a.note }) { Text(stringResource(R.string.annotate_edit)) }
                        TextButton(onClick = { scope.launch { runCatching { graph.reader.annotations.delete(a.id) }; onChanged() } }) { Text(stringResource(R.string.action_delete)) }
                    }
                }
            }
        }
    }
    editing?.let { a ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(kindLabel(a.kind)) },
            text = { OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.annotate_note_text)) }) },
            confirmButton = {
                TextButton(onClick = {
                    editing = null
                    scope.launch { runCatching { graph.reader.annotations.update(a.id, note = draft) }; onChanged() }
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The document's words (§6.4, D-165): every looked-up word, "add all to reviews", and the context-card drill. */
@Composable
private fun WordsTab(docId: String, onOpenEntry: (Long) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<DocumentWord>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var drill by remember { mutableStateOf<ContextDrill?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) {
        runCatching { graph.reader.vocabulary.words(docId) }.onSuccess { words = it; error = null }.onFailure { error = it.readable() }
    }
    val d = drill
    if (d != null) {
        DrillView(d, onExit = { drill = null })
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.words_intro), style = MaterialTheme.typography.bodySmall)
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { version++ }) }
        val list = words
        when {
            list == null && error == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list != null && list.isEmpty() -> Text(stringResource(R.string.words_none), style = MaterialTheme.typography.bodyMedium)
            list != null -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { runCatching { graph.reader.vocabulary.drill(docId) }.onSuccess { drill = it }.onFailure { note = it.readable() } } }) {
                        Text(stringResource(R.string.words_drill))
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            note = runCatching { graph.reader.addDocumentWordsToReviews(docId) }
                                .fold({ context.getString(R.string.words_added, it) }, { it.readable() })
                        }
                    }) { Text(stringResource(R.string.words_add_all)) }
                }
                note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
                list.forEach { w ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = w.entryId != null) { w.entryId?.let(onOpenEntry) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                                JaText(w.text, style = MaterialTheme.typography.titleMedium)
                                if (w.reading != w.text) JaText(w.reading, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(w.gloss, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            JaText(w.sentence, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            Text(stringResource(R.string.words_lookups, w.lookups), style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { scope.launch { runCatching { graph.reader.vocabulary.remove(docId, w.ref) }; version++ } }) { Text(stringResource(R.string.action_remove)) }
                    }
                }
            }
        }
    }
}

/** The context-card drill: the word inside its sentence; type the reading or the meaning, or reveal and self-grade. */
@Composable
private fun DrillView(drill: ContextDrill, onExit: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var result by remember(tick) { mutableStateOf<ContextCardResult?>(null) }
    var revealed by remember(tick) { mutableStateOf(false) }
    var typed by remember(tick) { mutableStateOf("") }
    var byMeaning by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val t = tick
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val card = drill.current
        val (right, answered) = drill.score
        Text(stringResource(R.string.drill_progress, drill.position + (if (card == null) 0 else 1), drill.cards.size, right, answered), style = MaterialTheme.typography.labelLarge)
        if (card == null && result == null) {
            Text(stringResource(R.string.drill_done, right, answered), style = MaterialTheme.typography.titleMedium)
            Button(onClick = onExit) { Text(stringResource(R.string.action_done)) }
            return@Column
        }
        val shown = result?.card ?: card ?: return@Column
        Text(
            ja(buildAnnotatedString {
                append(shown.before)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(shown.word) }
                append(shown.after)
            }),
            style = MaterialTheme.typography.headlineSmall.japanese(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(!byMeaning, { byMeaning = false }, { Text(stringResource(R.string.drill_reading)) })
            FilterChip(byMeaning, { byMeaning = true }, { Text(stringResource(R.string.drill_meaning)) })
        }
        val r = result
        if (r == null && !revealed) {
            OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(if (byMeaning) R.string.drill_type_meaning else R.string.drill_type_reading)) }, textStyle = MaterialTheme.typography.bodyLarge.japanese())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { result = if (byMeaning) drill.answerMeaning(typed) else drill.answerReading(typed) }, enabled = typed.isNotBlank()) { Text(stringResource(R.string.action_check)) }
                TextButton(onClick = { revealed = true }) { Text(stringResource(R.string.quiz_reveal)) }
            }
        } else {
            JaText(shown.reading, style = MaterialTheme.typography.titleMedium)
            Text(shown.gloss, style = MaterialTheme.typography.bodyMedium)
            if (r != null) {
                Text(stringResource(if (r.correct) R.string.review_correct else R.string.review_not_quite), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (r.correct) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
                Button(onClick = { tick++ }) { Text(stringResource(R.string.action_next)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { drill.grade(true); tick++ }) { Text(stringResource(R.string.drill_knew)) }
                    OutlinedButton(onClick = { drill.grade(false); tick++ }) { Text(stringResource(R.string.drill_didnt)) }
                }
            }
        }
        TextButton(onClick = onExit) { Text(stringResource(R.string.drill_stop)) }
    }
}
