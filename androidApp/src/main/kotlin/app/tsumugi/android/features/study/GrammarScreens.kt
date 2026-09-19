package app.tsumugi.android.features.study

import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.localized
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.Tag
import app.tsumugi.android.ui.japanese
import app.tsumugi.domain.ItemSource
import app.tsumugi.grammar.GrammarExample
import app.tsumugi.grammar.GrammarExercise
import app.tsumugi.grammar.GrammarPoint
import app.tsumugi.grammar.GrammarPointDetail
import app.tsumugi.grammar.GrammarPointStatus
import kotlinx.coroutines.launch

/** CLAUDE.md rule 10: anything an LLM wrote carries this badge until a human verifies it. */
@Composable
fun AiBadge() = Tag(stringResource(R.string.ai_badge_unreviewed))

@Composable
fun GrammarLevelsScreen(onOpenLevel: (Int) -> Unit, onLessons: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var levels by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(Unit) { levels = graph.grammar()?.levels() ?: emptyList() }
    val list = levels ?: return Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    Column(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            Text(stringResource(R.string.grammar_missing), Modifier.padding(24.dp))
            return@Column
        }
        Button(onClick = onLessons, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.grammar_learn_new)) }
        list.sortedDescending().forEach { level ->
            ListItem(modifier = Modifier.clickable { onOpenLevel(level) }, headlineContent = { Text("JLPT N$level") })
        }
    }
}

@Composable
fun GrammarLevelScreen(level: Int, onOpenPoint: (String) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var points by remember { mutableStateOf<List<GrammarPointStatus>>(emptyList()) }
    LaunchedEffect(level) { points = graph.grammar()?.points(level).orEmpty() }
    LazyColumn(Modifier.fillMaxSize()) {
        items(points, key = { it.point.id }) { p ->
            ListItem(
                modifier = Modifier.clickable { onOpenPoint(p.point.id) },
                headlineContent = { JaText(p.point.title, style = MaterialTheme.typography.titleMedium) },
                supportingContent = { Text(p.point.meaning) },
                trailingContent = {
                    // F-20: a point with no example sentences can't be practised yet; its cards are held, not due.
                    if (p.noExamples) Tag(stringResource(R.string.grammar_no_examples))
                    else Text(p.stage?.localized() ?: "", style = MaterialTheme.typography.labelSmall)
                },
            )
        }
    }
}

@Composable
fun GrammarPointScreen(id: String) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<GrammarPointDetail?>(null) }
    LaunchedEffect(id) { detail = graph.grammar()?.point(id) }
    val d = detail ?: return Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    // §6.6 monolingual mode: our Japanese explanation when the setting asks for it, with an English toggle.
    val explanation = app.tsumugi.android.features.courses.rememberGrammarExplanation(d.point)
    var showEnglish by remember(id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GrammarPointContent(d.point, d.examples, if (showEnglish) null else explanation)
        if (explanation?.language == app.tsumugi.courses.ExplanationLanguage.JAPANESE) {
            androidx.compose.material3.FilterChip(showEnglish, { showEnglish = !showEnglish }, { Text(stringResource(R.string.mono_show_english)) })
        }
        app.tsumugi.android.features.courses.GrammarMasteryRow(d.point.id)
        // §6.4 guides library: link-only explanations elsewhere, opened in the browser.
        app.tsumugi.android.features.reader.GuideLinks(d.point.id)
        if (d.stage == null) {
            Button(onClick = { scope.launch { graph.grammar()?.learn(listOf(d.point)); detail = graph.grammar()?.point(id) } }) { Text(stringResource(R.string.action_add_to_reviews)) }
        } else {
            Text(stringResource(R.string.grammar_in_reviews, d.stage!!.localized()), color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrammarPointContent(point: GrammarPoint, examples: List<GrammarExample>, explanation: app.tsumugi.courses.GrammarExplanation? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        JaText(point.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.displaySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("N${point.jlpt}")
            if (point.source != ItemSource.VERIFIED) AiBadge()
            point.textbooks.forEach { (book, chapter) -> Tag("$book $chapter") }
        }
        JaText(point.structure, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        app.tsumugi.android.features.courses.GrammarExplanationText(point, explanation)
        if (point.mistakes.isNotEmpty()) {
            Text(stringResource(R.string.grammar_watch_out), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            point.mistakes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        Text(stringResource(R.string.dict_examples), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        val voices = app.tsumugi.android.platform.rememberVoices()
        val scope = rememberCoroutineScope()
        examples.forEachIndexed { ord, ex ->
            Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    ja(
                        buildAnnotatedString {
                            append(ex.before)
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)) { append(ex.answer) }
                            append(ex.after)
                        },
                    ),
                    style = MaterialTheme.typography.bodyLarge.japanese(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ex.english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ex.isAiGenerated) AiBadge()
                }
            }
            // Rule 20: the pre-rendered example (grammar/<point>/<ord>) when the grammar audio pack is installed.
            val playLabel = stringResource(R.string.grammar_play_example, ord + 1)
            androidx.compose.material3.IconButton(
                onClick = { scope.launch { voices.sayClip(app.tsumugi.audio.AudioKeys.grammar(point.id, ord), ex.japanese) } },
                modifier = Modifier.semantics { contentDescription = playLabel },
            ) { Text("▶") }
            }
        }
        if (examples.any { !it.isAiGenerated }) {
            Text(stringResource(R.string.grammar_examples_credit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

/** Grammar lessons: read each point, then add the batch to reviews. */
@Composable
fun GrammarLessonScreen(onDone: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val scope = rememberCoroutineScope()
    var points by remember { mutableStateOf<List<GrammarPoint>?>(null) }
    var examples by remember { mutableStateOf<List<GrammarExample>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { points = graph.grammarLessons() }
    val list = points ?: return Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    LaunchedEffect(index, list) { list.getOrNull(index)?.let { examples = graph.grammar()?.examples(it.id).orEmpty() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            list.isEmpty() -> { Text(stringResource(R.string.grammar_none_left)); Button(onClick = onDone) { Text(stringResource(R.string.action_ok)) } }
            done -> { Text(stringResource(R.string.grammar_added, list.size), style = MaterialTheme.typography.titleMedium); Button(onClick = onDone) { Text(stringResource(R.string.action_done)) } }
            else -> {
                Text(stringResource(R.string.grammar_progress, index + 1, list.size), style = MaterialTheme.typography.labelLarge)
                GrammarPointContent(list[index], examples)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index > 0) OutlinedButton(onClick = { index-- }) { Text(stringResource(R.string.action_back)) }
                    Button(onClick = {
                        if (index < list.lastIndex) index++ else scope.launch { graph.grammar()?.learn(list); done = true }
                    }) { Text(stringResource(if (index < list.lastIndex) R.string.action_next else R.string.action_add_to_reviews)) }
                }
            }
        }
    }
}

/** Sentence building: tap chunks in order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildAnswer(exercise: GrammarExercise, resetKey: Any, enabled: Boolean = true, onSubmit: (String) -> Unit) {
    var picked by remember(resetKey) { mutableStateOf(listOf<Int>()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val built = picked.joinToString("") { exercise.chunks[it] }
        if (built.isEmpty()) {
            Text(stringResource(R.string.grammar_tap_pieces), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
        } else {
            JaText(built, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.headlineSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            exercise.chunks.forEachIndexed { i, chunk ->
                if (i !in picked) AssistChip(onClick = { picked = picked + i }, label = { JaText(chunk, style = MaterialTheme.typography.titleMedium) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picked = picked.dropLast(1) }, enabled = picked.isNotEmpty()) { Text(stringResource(R.string.action_undo)) }
            Button(onClick = { onSubmit(picked.joinToString("") { exercise.chunks[it] }) }, enabled = enabled && picked.size == exercise.chunks.size) { Text(stringResource(R.string.action_check)) }
        }
    }
}
