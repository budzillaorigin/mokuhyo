package app.tsumugi.android.features.tracks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
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
import app.tsumugi.practice.DialogueSummary
import app.tsumugi.practice.Scenario
import app.tsumugi.tracks.CultureTask
import app.tsumugi.tracks.DrillType
import app.tsumugi.tracks.PerformDrill
import app.tsumugi.tracks.Track
import app.tsumugi.tracks.TrackDrill
import app.tsumugi.tracks.TrackKanji
import app.tsumugi.tracks.TrackLesson
import app.tsumugi.tracks.TrackLink
import app.tsumugi.tracks.TrackReading
import app.tsumugi.tracks.TrackSituation
import app.tsumugi.tracks.TrackSummary
import kotlinx.coroutines.launch

/** Localized name of a drill type. */
@StringRes
internal fun DrillType.label(): Int = when (this) {
    DrillType.KEIGO -> R.string.track_drill_keigo
    DrillType.EMAIL -> R.string.track_drill_email
    DrillType.FILL_IN -> R.string.track_drill_fill_in
    DrillType.SYNONYM -> R.string.track_drill_synonym
    DrillType.USAGE -> R.string.track_drill_usage
    DrillType.MEANING -> R.string.track_drill_meaning
    DrillType.PERFORM -> R.string.track_drill_perform
}

/** "478 words · 142 kanji · 10 scenarios …" from the pack's per-track counts. */
@Composable
private fun countsLine(track: Track): String {
    val parts = mutableListOf(stringResource(R.string.track_count_words, track.count("words")), stringResource(R.string.track_count_kanji, track.count("kanji")))
    if (track.count("scenarios") > 0) parts += stringResource(R.string.track_count_scenarios, track.count("scenarios"))
    if (track.count("dialogues") > 0) parts += stringResource(R.string.track_count_dialogues, track.count("dialogues"))
    if (track.count("drills") > 0) parts += stringResource(R.string.track_count_drills, track.count("drills"))
    return parts.joinToString(" · ")
}

/** Learn → Tracks (BRIEF_V2 §6.5): pick any number of tracks, or switch to one; each opens its page. */
@Composable
fun TracksScreen(onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<TrackSummary>?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        error = null
        runCatching {
            missing = graph.trackRepository() == null
            graph.tracks.tracks()
        }.onSuccess { tracks = it }.onFailure { error = it.readable() }
    }
    fun change(action: suspend () -> Unit) = scope.launch {
        runCatching { action() }.onFailure { error = it.readable() }
        reload++
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { reload++ }, Modifier.padding(vertical = 8.dp)) }
        val list = tracks
        when {
            missing -> Notice(stringResource(R.string.track_pack_missing), Modifier.padding(vertical = 8.dp))
            list == null -> if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            list.isEmpty() -> Notice(stringResource(R.string.track_none), Modifier.padding(vertical = 8.dp))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text(stringResource(R.string.track_intro), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
                items(list, key = { it.track.id }) { s ->
                    TrackCard(
                        s,
                        onOpen = { onOpen(s.track.id) },
                        onToggle = { on -> change { if (on) graph.tracks.select(s.track.id) else graph.tracks.deselect(s.track.id) } },
                        onOnly = { change { graph.tracks.switchTo(s.track.id) } },
                    )
                }
                item { Text(stringResource(R.string.track_today_note), Modifier.padding(bottom = 16.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackCard(s: TrackSummary, onOpen: () -> Unit, onToggle: (Boolean) -> Unit, onOnly: () -> Unit) {
    val t = s.track
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = if (s.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    JaText(t.titleJa, style = MaterialTheme.typography.titleMedium)
                    Text(t.titleEn, style = MaterialTheme.typography.bodyMedium)
                }
                val label = stringResource(R.string.track_selected)
                Switch(s.selected, onCheckedChange = onToggle, modifier = Modifier.semantics { this.contentDescription = label })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag(t.levelLabel)
                if (t.isAiGenerated) AiBadge()
            }
            Text(t.description, style = MaterialTheme.typography.bodySmall)
            Text(countsLine(t), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.track_words_left, s.wordsLeft), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.action_open)) }
                TextButton(onClick = onOnly) { Text(stringResource(R.string.track_only_this)) }
            }
        }
    }
}

/** Optional onboarding step: choose tracks (none = main path only). Skipped when the pack has no tracks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TracksOnboardingStep(onDone: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var options by remember { mutableStateOf<List<TrackSummary>?>(null) }
    val chosen = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        val list = runCatching { graph.tracks.onboardingOptions() }.getOrDefault(emptyList())
        chosen.addAll(list.filter { it.selected }.map { it.track.id })
        options = list
    }
    val list = options
    when {
        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        list.isEmpty() -> LaunchedEffect(Unit) { onDone() }
        else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.track_onboarding_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.track_onboarding_body))
            list.forEach { s ->
                val on = s.track.id in chosen
                Card(
                    Modifier.fillMaxWidth().toggleable(on, role = Role.Checkbox) { v -> if (v) chosen += s.track.id else chosen -= s.track.id },
                    colors = CardDefaults.cardColors(containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(on, onCheckedChange = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            JaText(s.track.titleJa, style = MaterialTheme.typography.titleSmall)
                            Text("${s.track.titleEn} · ${s.track.levelLabel}", style = MaterialTheme.typography.bodySmall)
                            Text(s.track.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
            Button(onClick = {
                scope.launch {
                    runCatching { graph.tracks.chooseInOnboarding(chosen.toList()) }
                    onDone()
                }
            }) { Text(stringResource(R.string.action_next)) }
            TextButton(onClick = onDone) { Text(stringResource(R.string.track_onboarding_skip)) }
        }
    }
}

private enum class TrackTab(@StringRes val label: Int) {
    WORDS(R.string.track_tab_words), KANJI(R.string.track_tab_kanji), SPEAK(R.string.track_tab_speak), DRILLS(R.string.track_tab_drills),
    LIFE(R.string.track_tab_life), READ(R.string.track_tab_read),
}

/** Everything on a track page, loaded once. */
private class TrackPage(
    val track: Track,
    val selected: Boolean,
    val lessons: List<TrackLesson>,
    val kanji: List<TrackKanji>,
    val scenarios: List<Scenario>,
    val dialogues: List<DialogueSummary>,
    val drills: List<TrackDrill>,
    val situations: List<TrackSituation>,
    val tasks: List<CultureTask>,
    val readings: List<TrackReading>,
    val links: List<TrackLink>,
    val canDo: Set<String>,
)

/** A track's page: words (lessons join Today), kanji, scenarios and dialogues, drills, can-do lists and tasks, readings and links. */
@Composable
fun TrackScreen(
    id: String,
    onOpenScenario: (String) -> Unit,
    onOpenDialogue: (String) -> Unit,
    onOpenDrills: (trackId: String, type: String) -> Unit,
    onOpenPerformance: (String) -> Unit,
    onOpenKanji: (String) -> Unit,
    onOpenEntry: (Long) -> Unit,
    onStartLessons: () -> Unit,
) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var page by remember(id) { mutableStateOf<TrackPage?>(null) }
    var missing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, reload) {
        error = null
        runCatching {
            val repo = graph.trackRepository()
            if (repo == null) {
                missing = true
                return@runCatching null
            }
            val track = repo.track(id) ?: return@runCatching null
            TrackPage(
                track, id in graph.tracks.selected(), repo.lessons(id), repo.kanji(id), repo.scenarios(id), repo.dialogues(id),
                repo.drills(id), repo.situations(id), repo.tasks(id), repo.readings(id), repo.links(id), graph.tracks.canDoDone(),
            )
        }.onSuccess { p ->
            page = p
            if (p == null && !missing) error = "no track $id"
        }.onFailure { error = it.readable() }
    }
    val p = page
    when {
        missing -> Notice(stringResource(R.string.track_pack_missing), Modifier.padding(16.dp))
        error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { reload++ }, Modifier.padding(16.dp))
        p == null -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        else -> {
            val tabs = TrackTab.entries.filter { t ->
                when (t) {
                    TrackTab.WORDS -> true
                    TrackTab.KANJI -> p.kanji.isNotEmpty()
                    TrackTab.SPEAK -> p.scenarios.isNotEmpty() || p.dialogues.isNotEmpty()
                    TrackTab.DRILLS -> p.drills.isNotEmpty()
                    TrackTab.LIFE -> p.situations.isNotEmpty() || p.tasks.isNotEmpty()
                    TrackTab.READ -> p.readings.isNotEmpty() || p.links.isNotEmpty()
                }
            }
            var tab by remember(id) { mutableStateOf(TrackTab.WORDS) }
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JaText(p.track.titleJa, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.track.titleEn, style = MaterialTheme.typography.bodyMedium)
                        Tag(p.track.levelLabel)
                        if (p.track.isAiGenerated) AiBadge()
                    }
                }
                PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0), edgePadding = 8.dp) {
                    tabs.forEach { t -> Tab(selected = t == tab, onClick = { tab = t }, text = { Text(stringResource(t.label)) }) }
                }
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Row(Modifier.padding(top = 8.dp)) {} }
                    when (tab) {
                        TrackTab.WORDS -> wordsTab(
                            p, onOpenEntry, onStartLessons,
                            onSelect = { scope.launch { runCatching { graph.tracks.select(id) }.onFailure { error = it.readable() }; reload++ } },
                        )
                        TrackTab.KANJI -> items(p.kanji, key = { it.literal }) { KanjiRow(it, onOpenKanji) }
                        TrackTab.SPEAK -> speakTab(p, onOpenScenario, onOpenDialogue)
                        TrackTab.DRILLS -> drillsTab(p, onOpenDrills, onOpenPerformance)
                        TrackTab.LIFE -> lifeTab(p) { canDoId, done ->
                            scope.launch { runCatching { graph.tracks.setCanDo(canDoId, done) }.onFailure { error = it.readable() }; reload++ }
                        }
                        TrackTab.READ -> readTab(p)
                    }
                    item { Row(Modifier.padding(bottom = 24.dp)) {} }
                }
            }
        }
    }
}

private fun LazyListScope.wordsTab(p: TrackPage, onOpenEntry: (Long) -> Unit, onStartLessons: () -> Unit, onSelect: () -> Unit) {
    item {
        if (p.selected) {
            Notice(stringResource(R.string.track_words_today), actionLabel = stringResource(R.string.track_start_lessons), onAction = onStartLessons)
        } else {
            Notice(stringResource(R.string.track_words_select), actionLabel = stringResource(R.string.track_select), onAction = onSelect)
        }
    }
    p.lessons.forEach { lesson ->
        item(key = "lesson-${lesson.index}") {
            SectionTitle(stringResource(R.string.track_lesson, lesson.index + 1, lesson.topic))
        }
        items(lesson.words, key = { "w-${lesson.index}-${it.ord}" }) { w ->
            ListItem(
                modifier = Modifier.clickable { onOpenEntry(w.entryId) },
                headlineContent = { JaText(if (w.reading.isNotBlank() && w.reading != w.text) "${w.text}（${w.reading}）" else w.text) },
                supportingContent = {
                    Column {
                        Text(w.gloss)
                        if (w.note.isNotBlank()) Text(w.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        w.jlpt?.let { Tag("N$it") }
                        if (w.isAiGenerated) AiBadge()
                    }
                },
            )
        }
    }
}

@Composable
private fun KanjiRow(k: TrackKanji, onOpenKanji: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpenKanji(k.literal) }) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            JaText(k.literal, style = MaterialTheme.typography.displaySmall)
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k.keyword, style = MaterialTheme.typography.titleSmall)
                    if (k.isAiGenerated && (k.breakdown.isNotBlank() || k.hint.isNotBlank())) AiBadge()
                }
                if (k.components.isNotEmpty()) JaText(k.components.joinToString(" + "), style = MaterialTheme.typography.bodySmall)
                if (k.breakdown.isNotBlank()) Text(k.breakdown, style = MaterialTheme.typography.bodySmall)
                if (k.hint.isNotBlank()) Text(stringResource(R.string.track_kanji_hint, k.hint), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            }
        }
    }
}

private fun LazyListScope.speakTab(p: TrackPage, onOpenScenario: (String) -> Unit, onOpenDialogue: (String) -> Unit) {
    if (p.scenarios.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.track_scenarios)) }
        items(p.scenarios, key = { "s-${it.id}" }) { s ->
            ListItem(
                modifier = Modifier.clickable { onOpenScenario(s.id) },
                headlineContent = { JaText(s.titleJa) },
                supportingContent = { Text(s.titleEn) },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Tag("N${s.jlpt}")
                        if (s.isAiGenerated) AiBadge()
                    }
                },
            )
        }
    }
    if (p.dialogues.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.track_dialogues)) }
        items(p.dialogues, key = { "d-${it.id}" }) { d ->
            ListItem(
                modifier = Modifier.clickable { onOpenDialogue(d.id) },
                headlineContent = { JaText(d.title) },
                supportingContent = { Text(d.topic) },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Tag("N${d.jlpt}")
                        if (d.isAiGenerated) AiBadge()
                    }
                },
            )
        }
    }
}

private fun LazyListScope.drillsTab(p: TrackPage, onOpenDrills: (String, String) -> Unit, onOpenPerformance: (String) -> Unit) {
    val byType = p.drills.groupBy { it.type }
    byType.filterKeys { it != DrillType.PERFORM }.forEach { (type, list) ->
        item(key = "t-${type.code}") {
            ListItem(
                modifier = Modifier.clickable { onOpenDrills(p.track.id, type.code) },
                headlineContent = { Text(stringResource(type.label())) },
                supportingContent = { Text(stringResource(R.string.track_drill_count, list.size)) },
                trailingContent = { if (list.any { it.isAiGenerated }) AiBadge() },
            )
        }
    }
    byType[DrillType.PERFORM]?.let { performances ->
        item { SectionTitle(stringResource(R.string.track_drill_perform)) }
        item { Text(stringResource(R.string.track_perform_intro), style = MaterialTheme.typography.bodySmall) }
        items(performances.filterIsInstance<PerformDrill>(), key = { "p-${it.id}" }) { d ->
            ListItem(
                modifier = Modifier.clickable { onOpenPerformance(d.id) },
                headlineContent = { JaText(d.titleJa) },
                supportingContent = { Text("${d.title} · ${d.setting}") },
                trailingContent = { if (d.isAiGenerated) AiBadge() },
            )
        }
    }
}

private fun LazyListScope.lifeTab(p: TrackPage, onCanDo: (String, Boolean) -> Unit) {
    if (p.situations.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.track_can_do)) }
        items(p.situations, key = { "sit-${it.id}" }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        JaText(s.titleJa, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (s.isAiGenerated) AiBadge()
                    }
                    Text(s.titleEn, style = MaterialTheme.typography.bodySmall)
                    val done = s.canDo.indices.count { s.canDoId(it) in p.canDo }
                    Text(stringResource(R.string.track_can_do_progress, done, s.canDo.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    s.canDo.forEachIndexed { i, c ->
                        val on = s.canDoId(i) in p.canDo
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Checkbox) { onCanDo(s.canDoId(i), it) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, onCheckedChange = null)
                            Column(Modifier.padding(start = 8.dp)) {
                                JaText(c.ja, style = MaterialTheme.typography.bodyMedium)
                                Text(c.en, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
    if (p.tasks.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.track_tasks)) }
        items(p.tasks, key = { "task-${it.id}" }) { TaskCard(it) }
    }
}

@Composable
private fun TaskCard(t: CultureTask) {
    var open by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { open = !open }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                JaText(t.titleJa, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (t.isAiGenerated) AiBadge()
            }
            Text("${t.titleEn} · ${t.place}", style = MaterialTheme.typography.bodySmall)
            if (!open) {
                Text(stringResource(R.string.track_task_show), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                TaskList(R.string.track_task_before, t.before)
                TaskList(R.string.track_task_during, t.during)
                TaskList(R.string.track_task_after, t.after)
                TaskList(R.string.track_task_phrases, t.phrases, japanese = true)
                TaskList(R.string.track_task_etiquette, t.etiquette)
            }
        }
    }
}

@Composable
private fun TaskList(@StringRes title: Int, lines: List<String>, japanese: Boolean = false) {
    if (lines.isEmpty()) return
    Text(stringResource(title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    lines.forEach { if (japanese) JaText("• $it", style = MaterialTheme.typography.bodyMedium) else Text("• $it", style = MaterialTheme.typography.bodyMedium) }
}

private fun LazyListScope.readTab(p: TrackPage) {
    if (p.readings.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.track_readings)) }
        items(p.readings, key = { "r-${it.id}" }) { ReadingCard(it) }
    }
    if (p.links.isNotEmpty()) {
        item { SectionTitle(stringResource(R.string.track_links)) }
        item { Text(stringResource(R.string.track_links_note), style = MaterialTheme.typography.bodySmall) }
        items(p.links, key = { "l-${it.url}" }) { LinkRow(it) }
    }
}

@Composable
private fun ReadingCard(r: TrackReading) {
    var open by remember { mutableStateOf(false) }
    val chosen = remember { mutableStateMapOf<Int, Int>() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                JaText(r.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Tag("ILR ${r.ilr}")
                if (r.isAiGenerated) AiBadge()
            }
            Text(r.genre, style = MaterialTheme.typography.bodySmall)
            if (!open) {
                TextButton(onClick = { open = true }) { Text(stringResource(R.string.track_reading_open)) }
            } else {
                JaText(r.body, style = MaterialTheme.typography.bodyLarge)
                r.questions.forEachIndexed { qi, q ->
                    JaText("${qi + 1}. ${q.question}", style = MaterialTheme.typography.titleSmall)
                    q.choices.forEachIndexed { ci, choice ->
                        val answered = chosen[qi]
                        val color = when {
                            answered == null -> MaterialTheme.colorScheme.surface
                            ci == q.answer -> Color(0xFFC8E6C9)
                            ci == answered -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                        Card(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = answered == ci, enabled = answered == null, role = Role.RadioButton) { chosen[qi] = ci },
                            colors = CardDefaults.cardColors(containerColor = color),
                        ) { JaText(choice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (r.questions.isNotEmpty() && chosen.size == r.questions.size) {
                    val right = r.questions.indices.count { chosen[it] == r.questions[it].answer }
                    Text(stringResource(R.string.listen_score, right, r.questions.size), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { chosen.clear() }) { Text(stringResource(R.string.action_again)) }
                }
            }
        }
    }
}

@Composable
private fun LinkRow(link: TrackLink) {
    val context = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable {
            failed = try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                false
            } catch (e: ActivityNotFoundException) {
                true
            }
        },
        headlineContent = { JaText(link.title) },
        supportingContent = {
            Column {
                if (link.note.isNotBlank()) Text(link.note)
                Text(link.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                if (failed) Text(stringResource(R.string.track_link_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = { Text("↗") },
    )
}
