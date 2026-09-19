package app.tsumugi.android.features.readers

import androidx.annotation.StringRes
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
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
import app.tsumugi.android.ui.japanese
import app.tsumugi.android.ui.readable
import app.tsumugi.reader.GradedPassageSummary
import app.tsumugi.reader.GradedStory
import app.tsumugi.reader.ReadAlongTrack
import app.tsumugi.reader.ReaderLevelInfo
import app.tsumugi.reader.ReaderQuizResult
import app.tsumugi.reader.ReaderTask
import app.tsumugi.reader.SummaryGradeResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Level 0 is stored as N6 (D-201); everything else is its JLPT label. */
@Composable
private fun levelName(level: String?): String = when (level) {
    null -> ""
    "N6" -> stringResource(R.string.gr_level_zero)
    else -> level
}

@Composable
private fun genreName(genre: String?): String {
    val res = when (genre) {
        "story" -> R.string.gr_genre_story
        "manga" -> R.string.gr_genre_manga
        "news" -> R.string.gr_genre_news
        "editorial" -> R.string.gr_genre_editorial
        "email" -> R.string.gr_genre_email
        "essay" -> R.string.gr_genre_essay
        "notice" -> R.string.gr_genre_notice
        "academic" -> R.string.gr_genre_academic
        "ad" -> R.string.gr_genre_ad
        "recipe" -> R.string.gr_genre_recipe
        else -> null
    }
    return res?.let { stringResource(it) } ?: genre.orEmpty()
}

/** Difficulty badge: the build's §6.4 label and score ("N4 · ILR 1 · 38"). */
@Composable
private fun DifficultyBadge(label: String?, score: Int?) {
    val text = listOfNotNull(label?.takeIf { it.isNotBlank() }, score?.let { stringResource(R.string.gr_score, it) }).joinToString(" · ")
    if (text.isNotEmpty()) Tag(text)
}

// --- Library ------------------------------------------------------------------------------------------------------

/** Graded readers by level (level 0 → N1), with genre chips and the difficulty badge (BRIEF_V2 §6.4). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradedLibraryScreen(onOpen: (String) -> Unit) {
    val graph = rememberGraph()
    var levels by remember { mutableStateOf<List<ReaderLevelInfo>?>(null) }
    var jlpt by remember { mutableStateOf<Int?>(null) }
    var genre by remember { mutableStateOf<String?>(null) }
    var stories by remember { mutableStateOf<List<GradedPassageSummary>?>(null) }
    var comprehension by remember { mutableStateOf<Double?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        error = null
        runCatching {
            val all = graph.reader.graded.levels()
            levels = all
            if (jlpt == null) jlpt = all.firstOrNull { it.storyCount > 0 }?.jlpt
            comprehension = graph.reader.graded.comprehensionPercent()
        }.onFailure { error = it.readable() }
    }
    LaunchedEffect(jlpt, attempt) {
        val level = jlpt ?: return@LaunchedEffect
        stories = null
        runCatching { graph.reader.graded.stories(level) }
            .onSuccess { stories = it; if (genre != null && it.none { s -> s.genre == genre }) genre = null }
            .onFailure { error = it.readable() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { attempt++ }, Modifier.padding(top = 8.dp)) }
        val all = levels
        when {
            all == null -> if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            all.isEmpty() || all.all { it.storyCount == 0 } -> Notice(stringResource(R.string.gr_pack_missing), Modifier.padding(top = 8.dp))
            else -> {
                comprehension?.let {
                    Text(stringResource(R.string.gr_comprehension, it.toInt()), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    all.forEach { l ->
                        FilterChip(
                            jlpt == l.jlpt, { jlpt = l.jlpt },
                            { Text(stringResource(R.string.gr_level_chip, levelName(l.level), l.storyCount)) },
                            Modifier.semantics { role = Role.RadioButton },
                        )
                    }
                }
                val list = stories
                val genres = list.orEmpty().mapNotNull { it.genre }.distinct().sorted()
                if (genres.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(genre == null, { genre = null }, { Text(stringResource(R.string.filter_all)) })
                        genres.forEach { g -> FilterChip(genre == g, { genre = g }, { Text(genreName(g)) }) }
                    }
                }
                when {
                    list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    list.isEmpty() -> Text(stringResource(R.string.gr_no_stories), Modifier.padding(8.dp))
                    else -> LazyColumn {
                        items(list.filter { genre == null || it.genre == genre }, key = { it.id }) { s ->
                            ListItem(
                                modifier = Modifier.clickable { onOpen(s.id) },
                                headlineContent = { JaText(s.title, style = MaterialTheme.typography.titleSmall) },
                                supportingContent = {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        s.titleEn?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            s.genre?.let { Tag(genreName(it)) }
                                            DifficultyBadge(s.label, s.textScore)
                                            Tag(stringResource(R.string.gr_chars, s.length))
                                            if (s.source != "verified") AiBadge()
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

// --- Story --------------------------------------------------------------------------------------------------------

private enum class StoryTab(@StringRes val label: Int) {
    READ(R.string.gr_tab_read), WORDS(R.string.gr_tab_words), QUIZ(R.string.gr_tab_quiz), TASKS(R.string.gr_tab_tasks),
}

/** One graded story: read-along, vocabulary, comprehension quiz and the genre tasks. */
@Composable
fun GradedStoryScreen(id: String, onOpenEntry: (Long) -> Unit, onOpenAiSettings: () -> Unit, onOpenDraft: (String) -> Unit = {}) {
    val graph = rememberGraph()
    var story by remember(id) { mutableStateOf<GradedStory?>(null) }
    var track by remember(id) { mutableStateOf<ReadAlongTrack?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(id, attempt) {
        error = null
        runCatching {
            val s = graph.reader.graded.story(id)
            story = s
            track = s?.let { graph.reader.graded.readAlong(it) }
        }.onFailure { error = it.readable() }
        loaded = true
    }
    val s = story
    when {
        error != null -> ErrorState(stringResource(R.string.error_loading, error!!), onRetry = { attempt++ }, Modifier.padding(16.dp))
        !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        s == null -> Notice(stringResource(R.string.gr_story_missing), Modifier.padding(16.dp))
        else -> StoryView(s, track ?: ReadAlongTrack.of(s) { null }, onOpenEntry, onOpenAiSettings, onOpenDraft)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StoryView(story: GradedStory, track: ReadAlongTrack, onOpenEntry: (Long) -> Unit, onOpenAiSettings: () -> Unit, onOpenDraft: (String) -> Unit) {
    // §6.11: reading a graded story counts as active immersion.
    app.tsumugi.android.features.immersion.ImmersionTracker(
        app.tsumugi.immersion.ImmersionOrigin.READER, app.tsumugi.immersion.ImmersionMode.ACTIVE, story.id, story.title,
    )
    var tab by remember { mutableStateOf(StoryTab.READ) }
    val tabs = StoryTab.entries.filter {
        when (it) {
            StoryTab.WORDS -> story.vocabulary.isNotEmpty()
            StoryTab.QUIZ -> story.questions.isNotEmpty()
            StoryTab.TASKS -> story.tasks.all.isNotEmpty()
            StoryTab.READ -> true
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            JaText(story.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            if (story.titleEn.isNotBlank()) Text(story.titleEn, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag(levelName(story.level))
                Tag(genreName(story.genre))
                DifficultyBadge(story.label, story.textScore)
                if (story.isAiGenerated) AiBadge()
            }
        }
        PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab).coerceAtLeast(0)) {
            tabs.forEach { t -> Tab(selected = t == tab, onClick = { tab = t }, text = { Text(stringResource(t.label)) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (tab) {
                StoryTab.READ -> ReadAlongView(story, track)
                StoryTab.WORDS -> WordsView(story, onOpenEntry)
                StoryTab.QUIZ -> QuizView(story)
                StoryTab.TASKS -> TasksView(story, onOpenAiSettings, onOpenDraft)
            }
        }
    }
}

/** A TTS voice hint for a cast voice: only "male"/"female" mean anything to the device voices. */
private fun hint(voice: String): String? = voice.takeIf { it == "male" || it == "female" }

/**
 * Read-along (D-207): with the readers audio pack, the clips play in order and the sentence being spoken is
 * highlighted; without it, each sentence has its own ▶ that reads it with the device voice.
 */
@Composable
private fun ReadAlongView(story: GradedStory, track: ReadAlongTrack) {
    val voices = rememberVoices()
    val scope = rememberCoroutineScope()
    var playing by remember { mutableIntStateOf(-1) }
    var job by remember { mutableStateOf<Job?>(null) }
    var rate by remember { mutableStateOf(1f) }

    fun play(from: Int, single: Boolean) {
        job?.cancel()
        voices.stop()
        job = scope.launch {
            try {
                val range = if (single) from..from else from..story.lines.lastIndex
                for (i in range) {
                    playing = i
                    val line = story.lines[i]
                    voices.sayClip(line.clipKey, story.text(line), hint(line.voice), rate)
                }
            } finally {
                playing = -1
            }
        }
    }

    Notice(stringResource(if (track.timed) R.string.gr_audio_pack else R.string.gr_audio_tts))
    if (story.lines.isEmpty()) {
        JaText(story.body, style = MaterialTheme.typography.bodyLarge)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { play(0, single = false) }) { PlayLabel(stringResource(R.string.listen_play_all)) }
        OutlinedButton(onClick = { job?.cancel(); voices.stop() }) { Text(stringResource(R.string.listen_stop)) }
        listOf(0.75f, 1f).forEach { r -> FilterChip(rate == r, { rate = r }, { Text("${r}×") }) }
    }
    var previousEnd = 0
    story.lines.forEachIndexed { i, line ->
        // Keep the story's paragraph breaks between read-along lines.
        val gap = story.body.substring(previousEnd.coerceAtMost(line.start), line.start)
        if (i > 0 && gap.contains("\n")) Spacer(Modifier.padding(top = 4.dp))
        previousEnd = line.end
        val active = i == playing
        Row(
            Modifier.fillMaxWidth()
                .then(if (track.timed) Modifier.clickable { play(i, single = false) } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    if (line.speaker.isNotBlank() && line.speaker != "narrator") {
                        JaText(line.speaker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    JaText(story.text(line).trim(), style = MaterialTheme.typography.bodyLarge)
                }
            }
            val label = stringResource(R.string.gr_play_sentence, i + 1)
            IconButton(onClick = { play(i, single = true) }, modifier = Modifier.semantics { contentDescription = label }) { Text("▶") }
        }
    }
}

@Composable
private fun WordsView(story: GradedStory, onOpenEntry: (Long) -> Unit) {
    Text(stringResource(R.string.gr_words_hint), style = MaterialTheme.typography.bodySmall)
    story.vocabulary.forEach { v ->
        ListItem(
            modifier = Modifier.clickable { onOpenEntry(v.entryId) },
            headlineContent = { JaText(v.word, style = MaterialTheme.typography.titleMedium) },
            overlineContent = { if (v.reading != v.word) JaText(v.reading) },
            supportingContent = { Text(v.gloss) },
        )
        HorizontalDivider()
    }
}

@Composable
private fun QuizView(story: GradedStory) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val chosen = remember(story.id) { mutableStateMapOf<Int, Int>() }
    var result by remember(story.id) { mutableStateOf<ReaderQuizResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val done = result != null
    story.questions.forEachIndexed { qi, q ->
        val jaQuestion = q.language == "ja"
        if (jaQuestion) JaText("${qi + 1}. ${q.stem}", style = MaterialTheme.typography.titleSmall)
        else Text("${qi + 1}. ${q.stem}", style = MaterialTheme.typography.titleSmall)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.choices.forEachIndexed { ci, choice ->
                val picked = chosen[qi] == ci
                val color = when {
                    done && ci == q.answer -> Color(0xFFC8E6C9)
                    done && picked -> MaterialTheme.colorScheme.errorContainer
                    picked -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Card(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = picked, enabled = !done, role = Role.RadioButton, onClick = { chosen[qi] = ci }),
                    colors = CardDefaults.cardColors(containerColor = color),
                ) {
                    if (jaQuestion) JaText(choice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    else Text(choice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium.japanese())
                }
            }
        }
        if (done && q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodySmall)
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val r = result
    if (r == null) {
        Button(
            onClick = {
                busy = true
                scope.launch {
                    runCatching { graph.reader.graded.submitQuiz(story, story.questions.indices.map { chosen[it] }) }
                        .onSuccess { result = it; error = null }
                        .onFailure { error = it.readable() }
                    busy = false
                }
            },
            enabled = !busy && chosen.size == story.questions.size,
        ) { Text(stringResource(R.string.gr_quiz_submit)) }
    } else {
        Text(
            stringResource(R.string.gr_quiz_result, r.correct, r.total, r.percent.toInt()),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium,
        )
        Text(stringResource(R.string.gr_quiz_saved), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { chosen.clear(); result = null }) { Text(stringResource(R.string.action_again)) }
    }
}

/** A task prompt in Japanese with the English below (the prompt the pack filled in is the Japanese one). */
@Composable
private fun TaskPrompt(task: ReaderTask) {
    JaText(task.promptJa.ifBlank { task.prompt }, style = MaterialTheme.typography.bodyLarge)
    if (task.promptEn.isNotBlank()) Text(task.promptEn, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TasksView(story: GradedStory, onOpenAiSettings: () -> Unit, onOpenDraft: (String) -> Unit) {
    val tasks = story.tasks
    tasks.prediction?.let { PredictionTask(it) }
    tasks.skim?.let { HorizontalDivider(); SkimTask(story, it) }
    if (tasks.close.isNotEmpty()) {
        HorizontalDivider()
        SectionTitle(stringResource(R.string.gr_task_close))
        tasks.close.forEach { CloseTask(it) }
    }
    tasks.output?.let { HorizontalDivider(); OutputTask(story, it, onOpenAiSettings, onOpenDraft) }
}

@Composable
private fun PredictionTask(task: ReaderTask) {
    var guess by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.gr_task_prediction))
    Text(stringResource(R.string.gr_task_prediction_hint), style = MaterialTheme.typography.bodySmall)
    TaskPrompt(task)
    OutlinedTextField(
        guess, { guess = it }, Modifier.fillMaxWidth(), enabled = !revealed,
        label = { Text(stringResource(R.string.gr_task_your_guess)) }, textStyle = MaterialTheme.typography.bodyLarge.japanese(),
    )
    if (!revealed) {
        OutlinedButton(onClick = { revealed = true }) { Text(stringResource(R.string.gr_task_read_done)) }
    } else {
        Text(stringResource(R.string.gr_task_prediction_check), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Skim/scan: the text shows only while the timer runs; afterwards the learner ticks what they found. */
@Composable
private fun SkimTask(story: GradedStory, task: ReaderTask) {
    val seconds = task.seconds ?: 60
    var left by remember { mutableIntStateOf(seconds) }
    var state by remember { mutableIntStateOf(0) } // 0 = not started, 1 = running, 2 = time up
    val found = remember { mutableStateListOf<Int>() }
    LaunchedEffect(state) {
        if (state != 1) return@LaunchedEffect
        left = seconds
        while (left > 0) {
            delay(1_000)
            left--
        }
        state = 2
    }
    SectionTitle(stringResource(R.string.gr_task_skim))
    TaskPrompt(task)
    if (task.find.isNotEmpty()) {
        Text(stringResource(R.string.gr_task_find), style = MaterialTheme.typography.titleSmall)
    }
    when (state) {
        0 -> {
            task.find.forEach { JaText("• $it", style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = { found.clear(); state = 1 }) { Text(stringResource(R.string.gr_task_skim_start, seconds)) }
        }
        1 -> {
            task.find.forEach { JaText("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text(
                stringResource(R.string.gr_task_seconds_left, left),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            )
            LinearProgressIndicator(progress = { left.toFloat() / seconds }, modifier = Modifier.fillMaxWidth())
            Card(Modifier.fillMaxWidth()) { JaText(story.body, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge) }
            OutlinedButton(onClick = { state = 2 }) { Text(stringResource(R.string.gr_task_skim_stop)) }
        }
        else -> {
            Text(stringResource(R.string.gr_task_time_up), style = MaterialTheme.typography.titleSmall)
            task.find.forEachIndexed { i, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(i in found, { on -> if (on) found.add(i) else found.remove(i) })
                    JaText(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (task.find.isNotEmpty()) Text(stringResource(R.string.gr_task_found, found.size, task.find.size), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { state = 0 }) { Text(stringResource(R.string.action_again)) }
        }
    }
}

@Composable
private fun CloseTask(task: ReaderTask) {
    var notes by remember { mutableStateOf("") }
    TaskPrompt(task)
    OutlinedTextField(
        notes, { notes = it }, Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.gr_task_notes)) }, textStyle = MaterialTheme.typography.bodyLarge.japanese(),
    )
}

/** Post-reading output: a Japanese summary graded by the learner's model (always labeled AI, rule 10). */
@Composable
private fun OutputTask(story: GradedStory, task: ReaderTask, onOpenAiSettings: () -> Unit, onOpenDraft: (String) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var summary by remember(story.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember(story.id) { mutableStateOf<SummaryGradeResult?>(null) }
    val length = summary.trim().length
    val min = task.minChars
    val max = task.maxChars
    SectionTitle(stringResource(R.string.gr_task_output))
    TaskPrompt(task)
    OutlinedTextField(
        summary, { summary = it; result = null }, Modifier.fillMaxWidth().heightIn(min = 120.dp),
        label = { Text(stringResource(R.string.gr_task_summary)) }, textStyle = MaterialTheme.typography.bodyLarge.japanese(),
        supportingText = {
            Text(
                when {
                    min != null && max != null -> stringResource(R.string.gr_task_chars_range, length, min, max)
                    else -> stringResource(R.string.gr_task_chars, length)
                },
                color = if ((min != null && length < min) || (max != null && length > max)) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                busy = true
                scope.launch {
                    result = runCatching { graph.reader.graded.gradeSummary(story, summary) }
                        .getOrElse { SummaryGradeResult.Unavailable(it.readable()) }
                    busy = false
                }
            },
            enabled = !busy && summary.isNotBlank(),
        ) { Text(stringResource(R.string.gr_task_grade)) }
        AiBadge()
    }
    // §6.13: the output task can also be written as a saved draft in the writing studio (graded there too).
    var draftError by remember { mutableStateOf<String?>(null) }
    OutlinedButton(onClick = {
        scope.launch {
            draftError = null
            runCatching { graph.writingStudio.draftForReaderTask(story) }
                .onSuccess { onOpenDraft(it.id) }
                .onFailure { draftError = it.readable() }
        }
    }) { Text(stringResource(R.string.ws_write_in_studio)) }
    draftError?.let { Notice(stringResource(R.string.error_loading, it)) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    when (val r = result) {
        is SummaryGradeResult.Graded -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.gr_grade_total, r.grade.total), style = MaterialTheme.typography.titleMedium)
                    AiBadge(r.engine)
                }
                Text(stringResource(R.string.gr_grade_parts, r.grade.content, r.grade.accuracy, r.grade.language), style = MaterialTheme.typography.bodyMedium)
                Text(r.grade.feedback, style = MaterialTheme.typography.bodyMedium)
                if (r.grade.corrected.isNotBlank()) {
                    Text(stringResource(R.string.gr_grade_corrected), style = MaterialTheme.typography.titleSmall)
                    JaText(r.grade.corrected, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        is SummaryGradeResult.Unavailable -> Notice(
            context.getString(R.string.gr_grade_unavailable, r.reason),
            actionLabel = stringResource(R.string.ai_open_settings), onAction = onOpenAiSettings,
        )
        null -> Unit
    }
}
