package app.tsumugi.android.features.decks

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.app.displayName
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.coverage.KnownWords
import app.tsumugi.coverage.WordState
import app.tsumugi.decks.CoreDeck
import app.tsumugi.decks.DeckLessonMode
import app.tsumugi.decks.DeckLessonSettings
import app.tsumugi.decks.DeckWord
import app.tsumugi.decks.FrequencyDeckSummary
import app.tsumugi.decks.MediaDeckDetail
import app.tsumugi.decks.MediaDeckStats
import app.tsumugi.decks.MediaDeckSummary
import app.tsumugi.decks.MediaVocabulary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** What a new media deck is built from (BRIEF_V2 §6.1): a reader document, an EPUB, subtitles or pasted text. */
sealed interface DeckSource {
    data class Document(val docId: String) : DeckSource
    data class Epub(val path: String) : DeckSource
    data class Subtitles(val title: String, val text: String, val mediaKey: String?) : DeckSource
    data class Text(val title: String, val text: String) : DeckSource
}

/** Where deck screens go. */
class DeckNav(
    val create: (DeckSource) -> Unit,
    val openDeck: (String) -> Unit,
    val openCore: (String) -> Unit,
    val openEntry: (Long) -> Unit,
    val openGrammar: (String) -> Unit,
    val startLessons: () -> Unit,
    val openKnownWords: () -> Unit = {},
)

/** Subtitle and text files: UTF-8, else Shift_JIS (older Japanese subtitles). */
suspend fun readTextFile(context: android.content.Context, uri: android.net.Uri): String = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("can't open the file")
    try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("Shift_JIS"))
    }
}

/** Learn → Decks: media decks, Core 2k/6k/10k, the lesson deck and "create a deck" from files or text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DecksScreen(nav: DeckNav) {
    val graph = rememberGraph()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var decks by remember { mutableStateOf<List<MediaDeckSummary>?>(null) }
    var core by remember { mutableStateOf<List<FrequencyDeckSummary>>(emptyList()) }
    var lessons by remember { mutableStateOf<DeckLessonSettings?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var pasting by remember { mutableStateOf(false) }
    var pasteTitle by remember { mutableStateOf("") }
    var pasteText by remember { mutableStateOf("") }
    var pickError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(version) {
        runCatching {
            decks = graph.decks.decks()
            core = graph.decks.frequencyDecks()
            lessons = graph.deckLessons.settings()
        }.onSuccess { error = null }.onFailure { error = it.readable() }
    }
    val epubPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val file = File(context.cacheDir, "deck-${System.currentTimeMillis()}.epub")
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } } }
                file.path
            }.onSuccess { nav.create(DeckSource.Epub(it)) }.onFailure { pickError = it.readable() }
        }
    }
    val subsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { displayName(context, uri).substringBeforeLast('.') to readTextFile(context, uri) }
                .onSuccess { (title, text) -> nav.create(DeckSource.Subtitles(title, text, null)) }
                .onFailure { pickError = it.readable() }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.decks_intro), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pasting = true; pasteText = ""; pasteTitle = "" }) { Text(stringResource(R.string.deck_from_text)) }
            OutlinedButton(onClick = { epubPicker.launch(arrayOf("application/epub+zip", "*/*")) }) { Text("EPUB") }
            OutlinedButton(onClick = { subsPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.deck_from_subtitles)) }
        }
        Text(stringResource(R.string.deck_from_reader_hint), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = nav.openKnownWords) { Text(stringResource(R.string.known_step_open)) }
        pickError?.let { ErrorState(stringResource(R.string.deck_pick_failed, it), onRetry = { pickError = null }) }
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { version++ }) }

        lessons?.let { l -> LessonDeckCard(l, decks.orEmpty(), onChanged = { version++ }, onStart = nav.startLessons) }

        Text(stringResource(R.string.decks_mine), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        val list = decks
        when {
            list == null && error == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list != null && list.isEmpty() -> Text(stringResource(R.string.decks_none), style = MaterialTheme.typography.bodyMedium)
            list != null -> list.forEach { d ->
                ListItem(
                    modifier = Modifier.clickable { nav.openDeck(d.id) },
                    headlineContent = { JaText(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 2) },
                    supportingContent = {
                        Column {
                            Text(coverageSummary(d.coverage), style = MaterialTheme.typography.bodySmall)
                            Text(
                                stringResource(R.string.deck_row_info, d.wordCount, levelLabel(d.stats.jlpt, d.stats.ilr), (d.libraryCoverage * 100).toInt()),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }
        }

        HorizontalDivider()
        Text(stringResource(R.string.decks_core), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (core.isEmpty()) {
            Text(stringResource(R.string.decks_core_missing), style = MaterialTheme.typography.bodySmall)
        }
        core.forEach { c ->
            ListItem(
                modifier = Modifier.clickable { nav.openCore(c.deck.id) },
                headlineContent = { Text(c.deck.title) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.core_known, c.known, c.size, c.learning), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (c.libraryCoverage > 0) stringResource(R.string.core_covers, (c.libraryCoverage * 100).toInt())
                            else stringResource(R.string.core_covers_none),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                },
            )
        }
    }

    if (pasting) {
        AlertDialog(
            onDismissRequest = { pasting = false },
            title = { Text(stringResource(R.string.deck_from_text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pasteTitle, { pasteTitle = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.deck_title)) }, singleLine = true)
                    OutlinedTextField(pasteText, { pasteText = it }, Modifier.fillMaxWidth(), minLines = 6, textStyle = MaterialTheme.typography.bodyLarge.japanese(), label = { Text(stringResource(R.string.deck_text)) })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pasting = false
                    nav.create(DeckSource.Text(pasteTitle.trim().ifEmpty { pasteText.trim().take(20) }, pasteText))
                }, enabled = pasteText.isNotBlank()) { Text(stringResource(R.string.deck_preview)) }
            },
            dismissButton = { TextButton(onClick = { pasting = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** The active lesson deck and its mode (INTERLEAVE with the kanji path, or DECK_ONLY), D-154. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LessonDeckCard(settings: DeckLessonSettings, decks: List<MediaDeckSummary>, onChanged: () -> Unit, onStart: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    if (!settings.active) return
    val title = decks.firstOrNull { it.id == settings.deckId }?.title ?: CoreDeck.byId(settings.deckId.orEmpty())?.title ?: settings.deckId.orEmpty()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.deck_lessons_from, title), style = MaterialTheme.typography.titleSmall)
            ModeChips(settings.mode) { m -> scope.launch { runCatching { graph.deckLessons.setMode(m) }; onChanged() } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text(stringResource(R.string.deck_start_lessons)) }
                TextButton(onClick = { scope.launch { runCatching { graph.deckLessons.deactivate() }; onChanged() } }) { Text(stringResource(R.string.deck_stop)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChips(mode: DeckLessonMode, onPick: (DeckLessonMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(mode == DeckLessonMode.INTERLEAVE, { onPick(DeckLessonMode.INTERLEAVE) }, { Text(stringResource(R.string.deck_mode_interleave)) })
        FilterChip(mode == DeckLessonMode.DECK_ONLY, { onPick(DeckLessonMode.DECK_ONLY) }, { Text(stringResource(R.string.deck_mode_only)) })
    }
    Text(stringResource(if (mode == DeckLessonMode.DECK_ONLY) R.string.deck_mode_only_hint else R.string.deck_mode_interleave_hint), style = MaterialTheme.typography.bodySmall)
}

/** "Study this deck": activate with a mode, or change the mode when it's already the lesson deck. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StudyDeckCard(deckId: String, onStart: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var settings by remember { mutableStateOf<DeckLessonSettings?>(null) }
    var left by remember { mutableStateOf<Int?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(deckId, version) {
        settings = runCatching { graph.deckLessons.settings() }.getOrNull()
        left = if (settings?.deckId == deckId && settings?.active == true) runCatching { graph.deckLessons.available() }.getOrNull() else null
    }
    val s = settings ?: return
    val active = s.active && s.deckId == deckId
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (active) {
                Text(stringResource(R.string.deck_is_lesson_deck), style = MaterialTheme.typography.titleSmall)
                left?.let { Text(stringResource(R.string.deck_lessons_left, it), style = MaterialTheme.typography.bodySmall) }
                ModeChips(s.mode) { m -> scope.launch { runCatching { graph.deckLessons.setMode(m) }; version++ } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onStart, enabled = left != 0) { Text(stringResource(R.string.deck_start_lessons)) }
                    TextButton(onClick = { scope.launch { runCatching { graph.deckLessons.deactivate() }; version++ } }) { Text(stringResource(R.string.deck_stop)) }
                }
            } else {
                Text(stringResource(R.string.deck_study_hint), style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            runCatching { graph.deckLessons.activate(deckId, DeckLessonMode.INTERLEAVE) }
                                .onSuccess { note = context.getString(R.string.deck_activated) }.onFailure { note = it.readable() }
                            version++
                        }
                    }) { Text(stringResource(R.string.deck_study_interleave)) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            runCatching { graph.deckLessons.activate(deckId, DeckLessonMode.DECK_ONLY) }
                                .onSuccess { note = context.getString(R.string.deck_activated) }.onFailure { note = it.readable() }
                            version++
                        }
                    }) { Text(stringResource(R.string.deck_study_only)) }
                }
            }
            note?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Preview before saving: stats, coverage targets, JLPT/ILR estimate, kanji and grammar, then the word list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateDeckScreen(source: DeckSource, nav: DeckNav, onSaved: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var vocabulary by remember(source) { mutableStateOf<MediaVocabulary?>(null) }
    var progress by remember(source) { mutableStateOf<Double?>(0.0) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var missing by remember(source) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var title by remember(source) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(source, attempt) {
        error = null
        progress = 0.0
        try {
            val onProgress: (Double) -> Unit = { progress = it }
            val v = when (source) {
                is DeckSource.Document -> graph.decks.vocabularyForDocument(source.docId, onProgress)
                is DeckSource.Epub -> graph.decks.vocabularyForEpub(source.path, onProgress)
                is DeckSource.Subtitles -> graph.decks.vocabularyForSubtitles(source.title, source.text, source.mediaKey, onProgress)
                is DeckSource.Text -> graph.decks.vocabularyForText(source.title, source.text, onProgress = onProgress)
            }
            vocabulary = v
            missing = v == null
            title = v?.title.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.readable()
        } finally {
            progress = null
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val v = vocabulary
        when {
            error != null -> ErrorState(stringResource(R.string.deck_build_failed, error!!), onRetry = { attempt++ })
            missing -> Text(stringResource(R.string.deck_no_pack), style = MaterialTheme.typography.bodyMedium)
            v == null -> {
                // Rule 15: tokenizing runs in the shared core off the main thread; leaving the screen cancels it.
                Text(stringResource(R.string.deck_analyzing, ((progress ?: 0.0) * 100).toInt()), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                LinearProgressIndicator(progress = { (progress ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.deck_analyzing_hint), style = MaterialTheme.typography.bodySmall)
            }
            v.words.isEmpty() -> Text(stringResource(R.string.deck_empty), style = MaterialTheme.typography.bodyMedium)
            else -> {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.deck_title)) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.japanese())
                Text(coverageSummary(v.coverage), style = MaterialTheme.typography.bodyMedium)
                StatsCard(v.stats)
                Button(onClick = {
                    saving = true
                    scope.launch {
                        runCatching { graph.decks.save(v.copy(title = title.trim().ifEmpty { v.title })) }
                            .onSuccess { onSaved(it.id) }
                            .onFailure { error = it.readable() }
                        saving = false
                    }
                }, enabled = !saving) { Text(stringResource(R.string.deck_save, v.words.size)) }
                KanjiAndGrammar(v.kanji.map { it.kanji to it.count }, v.grammarPointIds, nav.openGrammar)
                Text(stringResource(R.string.deck_words_order), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                v.words.take(PREVIEW).forEach { w -> WordRow(w, nav.openEntry) }
                if (v.words.size > PREVIEW) Text(stringResource(R.string.deck_more_words, v.words.size - PREVIEW), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private const val PREVIEW = 100

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsCard(stats: MediaDeckStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.deck_stats_words, stats.uniqueWords, stats.wordTokens), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.deck_stats_targets), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(80 to stats.words80, 90 to stats.words90, 95 to stats.words95, 98 to stats.words98).forEach { (p, n) ->
                    Text(stringResource(R.string.deck_stats_target, p, n), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.deck_stats_level, levelLabel(stats.jlpt, stats.ilr), stats.textScore), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.deck_stats_kanji_grammar, stats.kanjiCount, stats.grammarCount), style = MaterialTheme.typography.bodySmall)
            if (stats.sampled) Text(stringResource(R.string.coverage_sampled), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KanjiAndGrammar(kanji: List<Pair<String, Int>>, grammar: List<String>, openGrammar: (String) -> Unit) {
    if (kanji.isNotEmpty()) {
        Text(stringResource(R.string.deck_kanji, kanji.size), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        JaText(kanji.take(120).joinToString(" ") { it.first }, style = MaterialTheme.typography.titleLarge)
    }
    if (grammar.isNotEmpty()) {
        Text(stringResource(R.string.deck_grammar, grammar.size), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            grammar.take(60).forEach { id -> TextButton(onClick = { openGrammar(id) }) { Text(id.substringAfter('-').replace('-', ' ')) } }
        }
    }
}

@Composable
private fun WordRow(w: DeckWord, openEntry: (Long) -> Unit, onKnown: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable { openEntry(w.entryId) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                JaText(w.text, style = MaterialTheme.typography.titleMedium)
                w.reading?.takeIf { it != w.text }?.let { JaText(it, style = MaterialTheme.typography.bodySmall) }
            }
            Text(stringResource(R.string.deck_word_info, w.count, wordStateLabel(w.state)), style = MaterialTheme.typography.labelSmall)
            w.context?.let { JaText(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        if (onKnown != null && w.state != WordState.KNOWN) TextButton(onClick = onKnown) { Text(stringResource(R.string.known_mark_short)) }
    }
}

/** A saved media deck: its coverage and stats, "study this deck", kanji, grammar and every word. */
@Composable
fun DeckDetailScreen(id: String, nav: DeckNav, onDeleted: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<MediaDeckDetail?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(id, version) {
        runCatching { graph.decks.deck(id) }.onSuccess { detail = it; error = null }.onFailure { error = it.readable() }
        loaded = true
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { version++ }) }
        val d = detail
        if (d == null) {
            if (loaded && error == null) Text(stringResource(R.string.deck_gone)) else if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        JaText(d.summary.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text(coverageSummary(d.summary.coverage), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.deck_library_cover, (d.summary.libraryCoverage * 100).toInt()), style = MaterialTheme.typography.bodySmall)
        StatsCard(d.summary.stats)
        StudyDeckCard(id, nav.startLessons)
        KanjiAndGrammar(d.kanji.map { it.kanji to it.count }, d.grammarPointIds, nav.openGrammar)
        Text(stringResource(R.string.deck_words_order), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        d.words.forEach { w ->
            WordRow(w, nav.openEntry, onKnown = { scope.launch { runCatching { graph.knownWords.markKnown(listOf(w.entryId), KnownWords.SOURCE_MANUAL) }; version++ } })
        }
        HorizontalDivider()
        TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.deck_delete)) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.deck_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        runCatching { graph.decks.delete(id) }.onSuccess { onDeleted() }.onFailure { error = it.readable() }
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A Core frequency deck: known counts, "covers X% of your media", study it, and its words 100 at a time. */
@Composable
fun CoreDeckScreen(id: String, nav: DeckNav) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val deck = CoreDeck.byId(id)
    var summary by remember(id) { mutableStateOf<FrequencyDeckSummary?>(null) }
    var words by remember(id) { mutableStateOf<List<DeckWord>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var exhausted by remember(id) { mutableStateOf(false) }
    fun loadMore() {
        if (loading || exhausted) return
        loading = true
        scope.launch {
            runCatching { graph.decks.frequencyDeckWords(id, words.size, 100) }
                .onSuccess { page -> words = words + page; exhausted = page.size < 100; error = null }
                .onFailure { error = it.readable() }
            loading = false
        }
    }
    LaunchedEffect(id) {
        summary = runCatching { graph.decks.frequencyDecks().firstOrNull { it.deck.id == id } }.getOrNull()
        loadMore()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(deck?.title ?: id, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.core_intro), style = MaterialTheme.typography.bodySmall)
        summary?.let { c ->
            Text(stringResource(R.string.core_known, c.known, c.size, c.learning), style = MaterialTheme.typography.bodyMedium)
            Text(
                if (c.libraryCoverage > 0) stringResource(R.string.core_covers, (c.libraryCoverage * 100).toInt()) else stringResource(R.string.core_covers_none),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        StudyDeckCard(id, nav.startLessons)
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { loadMore() }) }
        words.forEach { w ->
            WordRow(w, nav.openEntry, onKnown = {
                scope.launch {
                    runCatching { graph.knownWords.markKnown(listOf(w.entryId), KnownWords.SOURCE_MANUAL) }
                    words = words.map { if (it.entryId == w.entryId) it.copy(state = WordState.KNOWN) else it }
                }
            })
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!exhausted && !loading && words.isNotEmpty()) OutlinedButton(onClick = ::loadMore) { Text(stringResource(R.string.deck_load_more)) }
        if (!loading && words.isEmpty() && error == null) Text(stringResource(R.string.decks_core_missing), style = MaterialTheme.typography.bodySmall)
    }
}
