package app.tsumugi.android.features.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.SectionTitle
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.readable
import app.tsumugi.translation.PassageOrigin
import app.tsumugi.translation.TranslationAttempt
import app.tsumugi.translation.TranslationDirection
import app.tsumugi.translation.TranslationGenre
import app.tsumugi.translation.TranslationMode
import app.tsumugi.translation.TranslationPassage
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/*
 * Translation workbench (BRIEF_V2 §6.12, D-270…D-274, D-294/D-295). Passages, grading, the diff and the skill line all
 * come from the shared TranslationService; these screens only list, time, capture and show.
 */

/**
 * The learner's own imported passages for this process (D-294): `TranslationService.passage(id)` only knows pack
 * passages, and an import isn't stored until an attempt is saved (the attempt keeps its source text for history).
 */
internal object ImportedPassages {
    private val byId = ConcurrentHashMap<String, TranslationPassage>()
    fun put(p: TranslationPassage) { byId[p.id] = p }
    fun get(id: String): TranslationPassage? = byId[id]
}

/** Pack passage or this session's import; null when neither has it. */
internal suspend fun resolvePassage(graph: app.tsumugi.api.AppGraph, id: String): TranslationPassage? =
    ImportedPassages.get(id) ?: if (id.startsWith("user:")) null else graph.translationWorkbench.passage(id)

@Composable
internal fun genreLabel(code: String): String = when (TranslationGenre.of(code)) {
    TranslationGenre.NEWS -> stringResource(R.string.tw_genre_news)
    TranslationGenre.TECHNICAL -> stringResource(R.string.tw_genre_technical)
    TranslationGenre.LEGAL -> stringResource(R.string.tw_genre_legal)
    TranslationGenre.LITERARY -> stringResource(R.string.tw_genre_literary)
    TranslationGenre.DIALOGUE -> stringResource(R.string.tw_genre_dialogue)
    TranslationGenre.MILITARY -> stringResource(R.string.tw_genre_military)
    null -> code
}

@Composable
internal fun directionLabel(d: TranslationDirection): String =
    stringResource(if (d == TranslationDirection.JE) R.string.tw_dir_je else R.string.tw_dir_ej)

@Composable
private fun originLabel(o: PassageOrigin): String? = when (o) {
    PassageOrigin.READER -> stringResource(R.string.tw_origin_reader)
    PassageOrigin.AOZORA -> stringResource(R.string.tw_origin_aozora)
    PassageOrigin.TATOEBA -> stringResource(R.string.tw_origin_tatoeba)
    PassageOrigin.USER -> stringResource(R.string.tw_origin_user)
    PassageOrigin.ORIGINAL -> null
}

/** "1:05" for a duration. */
internal fun clock(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** Passage list by genre, direction and level, plus "translate your own text" (§6.12). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationScreen(onOpen: (String) -> Unit, onHistory: () -> Unit) {
    val graph = rememberGraph()
    var passages by remember { mutableStateOf<List<TranslationPassage>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var direction by rememberSaveable { mutableStateOf<String?>(null) }
    var level by rememberSaveable { mutableStateOf<String?>(null) }
    var importText by rememberSaveable { mutableStateOf("") }
    var importTitle by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(attempt) {
        error = null
        runCatching { graph.translationWorkbench.passages() }.onSuccess { passages = it }.onFailure { error = it.readable() }
    }
    val all = passages
    val levels = all.orEmpty().map { it.level }.filter { it.isNotBlank() }.distinct().sortedByDescending { it }
    val shown = all.orEmpty().filter {
        (genre == null || it.genre == genre) && (direction == null || it.direction.code == direction) && (level == null || it.level == level)
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.tw_intro), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onHistory) { Text(stringResource(R.string.tw_history)) }
                when {
                    error != null -> ErrorState(stringResource(R.string.tw_load_failed, error!!), onRetry = { attempt++ })
                    all == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    all.isEmpty() -> Notice(stringResource(R.string.tw_pack_missing))
                    else -> {
                        FilterRow(stringResource(R.string.tw_filter_genre)) {
                            FilterChip(genre == null, { genre = null }, { Text(stringResource(R.string.filter_all)) })
                            TranslationGenre.entries.filter { g -> all.any { it.genre == g.code } }.forEach { g ->
                                FilterChip(genre == g.code, { genre = g.code }, { Text(genreLabel(g.code)) })
                            }
                        }
                        FilterRow(stringResource(R.string.tw_filter_direction)) {
                            FilterChip(direction == null, { direction = null }, { Text(stringResource(R.string.filter_all)) })
                            TranslationDirection.entries.forEach { d -> FilterChip(direction == d.code, { direction = d.code }, { Text(directionLabel(d)) }) }
                        }
                        if (levels.isNotEmpty()) FilterRow(stringResource(R.string.tw_filter_level)) {
                            FilterChip(level == null, { level = null }, { Text(stringResource(R.string.filter_all)) })
                            levels.forEach { l -> FilterChip(level == l, { level = l }, { Text(l) }) }
                        }
                        Text(stringResource(R.string.tw_count, shown.size), style = MaterialTheme.typography.labelMedium)
                        if (shown.isEmpty()) Notice(stringResource(R.string.tw_no_match))
                    }
                }
            }
        }
        items(shown, key = { it.id }) { p -> PassageRow(p) { onOpen(p.id) } }
        item {
            Card(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.tw_import_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.tw_import_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(importTitle, { importTitle = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.tw_import_name)) })
                    OutlinedTextField(importText, { importText = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp), label = { Text(stringResource(R.string.tw_import_text)) })
                    Button(
                        onClick = {
                            val p = graph.translationWorkbench.importPassage(importText, importTitle)
                            ImportedPassages.put(p)
                            onOpen(p.id)
                        },
                        enabled = importText.isNotBlank(),
                    ) { Text(stringResource(R.string.tw_import_go)) }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, chips: @Composable () -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        chips()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PassageRow(p: TranslationPassage, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { if (p.direction.japaneseSource) JaText(p.title) else Text(p.title) },
        supportingContent = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag(genreLabel(p.genre))
                Tag(directionLabel(p.direction))
                if (p.level.isNotBlank()) Tag(p.level)
                originLabel(p.origin)?.let { Tag(it) }
                if (p.isAiGenerated) AiBadge()
            }
        },
    )
}

/** One passage: the text, its tags and the two modes; earlier attempts on it below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationPassageScreen(id: String, onStart: (String, TranslationMode) -> Unit) {
    val graph = rememberGraph()
    var passage by remember(id) { mutableStateOf<TranslationPassage?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempts by remember(id) { mutableStateOf<List<TranslationAttempt>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, retry) {
        error = null
        runCatching { resolvePassage(graph, id) }.onSuccess { passage = it }.onFailure { error = it.readable() }
        attempts = runCatching { graph.translationWorkbench.history(id) }.getOrDefault(emptyList())
        loaded = true
    }
    val p = passage
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            error != null -> ErrorState(stringResource(R.string.tw_load_failed, error!!), onRetry = { retry++ })
            !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth())
            p == null -> Notice(stringResource(if (id.startsWith("user:")) R.string.tw_imported_gone else R.string.tw_passage_missing))
            else -> {
                PassageHeader(p)
                PassageText(p)
                if (!p.hasReference) Text(stringResource(R.string.tw_no_reference), style = MaterialTheme.typography.bodySmall)
                Card(Modifier.fillMaxWidth().clickable { onStart(p.id, TranslationMode.WRITTEN) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.tw_written), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.tw_written_sub), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(Modifier.fillMaxWidth().clickable { onStart(p.id, TranslationMode.SIGHT) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.tw_sight), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.tw_sight_sub), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.tw_sight_limit, clock(graph.translationWorkbench.timeLimitMs(p))), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (attempts.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.tw_previous))
                    attempts.sortedByDescending { it.createdAt }.forEach { a -> AttemptSummaryRow(a) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PassageHeader(p: TranslationPassage) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (p.direction.japaneseSource) JaText(p.title, style = MaterialTheme.typography.titleLarge) else Text(p.title, style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Tag(genreLabel(p.genre))
            Tag(directionLabel(p.direction))
            if (p.level.isNotBlank()) Tag(p.level)
            if (p.ilr.isNotBlank()) Tag("ILR ${p.ilr}")
            originLabel(p.origin)?.let { Tag(it) }
            if (p.isAiGenerated) AiBadge()
        }
    }
}

@Composable
internal fun PassageText(p: TranslationPassage) {
    Card(Modifier.fillMaxWidth()) {
        if (p.direction.japaneseSource) {
            JaText(p.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
        } else {
            Text(p.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttemptSummaryRow(a: TranslationAttempt, modifier: Modifier = Modifier) {
    val day = remember(a.createdAt) {
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(a.createdAt))
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.tw_score, a.score), style = MaterialTheme.typography.titleMedium)
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(day, style = MaterialTheme.typography.bodySmall)
            Tag(stringResource(if (a.mode == TranslationMode.SIGHT) R.string.tw_mode_sight else R.string.tw_mode_written))
            if (a.isAiGraded) AiBadge(a.engine) else Tag(stringResource(R.string.tw_self_assessed))
            if (a.overTime) Tag(stringResource(R.string.tw_over_time))
        }
    }
}

/** Every attempt, newest first; expand one to see the source, the translation and the grade; delete with a tombstone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationHistoryScreen(onOpenPassage: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var list by remember { mutableStateOf<List<TranslationAttempt>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<TranslationAttempt?>(null) }
    LaunchedEffect(retry) {
        error = null
        runCatching { graph.translationWorkbench.history().sortedByDescending { it.createdAt } }.onSuccess { list = it }.onFailure { error = it.readable() }
    }
    deleting?.let { a ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.tw_delete_title)) },
            text = { Text(stringResource(R.string.tw_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        runCatching { graph.translationWorkbench.delete(a.id) }
                            .onSuccess { retry++ }
                            .onFailure { error = context.getString(R.string.tw_save_failed, it.readable()) }
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    val items = list
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            when {
                error != null -> ErrorState(stringResource(R.string.tw_history_failed, error!!), onRetry = { retry++ }, Modifier.padding(top = 8.dp))
                items == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                items.isEmpty() -> Notice(stringResource(R.string.tw_history_empty), Modifier.padding(top = 8.dp))
            }
        }
        items(items.orEmpty(), key = { it.id }) { a ->
            Card(Modifier.fillMaxWidth().clickable { expanded = if (expanded == a.id) null else a.id }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    AttemptSummaryRow(a)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Tag(genreLabel(a.genre))
                        Tag(directionLabel(a.direction))
                        if (a.level.isNotBlank()) Tag(a.level)
                    }
                    val source = if (expanded == a.id) a.sourceText else a.sourceText.take(60) + if (a.sourceText.length > 60) "…" else ""
                    if (a.direction.japaneseSource) JaText(source, style = MaterialTheme.typography.bodyMedium) else Text(source, style = MaterialTheme.typography.bodyMedium)
                    if (expanded == a.id) {
                        Text(stringResource(R.string.tw_your_translation), style = MaterialTheme.typography.titleSmall)
                        if (a.direction.japaneseSource) Text(a.attemptText) else JaText(a.attemptText)
                        CriteriaScores(a.accuracy, a.completeness, a.register, a.naturalness)
                        a.grade?.let { g ->
                            if (g.feedback.isNotBlank()) Text(g.feedback, style = MaterialTheme.typography.bodyMedium)
                            if (g.better.isNotBlank()) {
                                Text(stringResource(R.string.tw_better), style = MaterialTheme.typography.titleSmall)
                                if (a.direction.japaneseSource) Text(g.better) else JaText(g.better)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!a.passageId.startsWith("user:")) OutlinedButton(onClick = { onOpenPassage(a.passageId) }) { Text(stringResource(R.string.tw_open_passage)) }
                            TextButton(onClick = { deleting = a }) { Text(stringResource(R.string.action_delete)) }
                        }
                    }
                }
            }
        }
    }
}
