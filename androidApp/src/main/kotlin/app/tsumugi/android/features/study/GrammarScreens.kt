package app.tsumugi.android.features.study

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
fun AiBadge() = Tag("AI-generated · unreviewed")

@Composable
fun GrammarLevelsScreen(onOpenLevel: (Int) -> Unit, onLessons: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var levels by remember { mutableStateOf<List<Int>?>(null) }
    LaunchedEffect(Unit) { levels = graph.grammar()?.levels() ?: emptyList() }
    val list = levels ?: return Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
    Column(Modifier.fillMaxSize()) {
        if (list.isEmpty()) {
            Text("The grammar pack isn't installed in this build.", Modifier.padding(24.dp))
            return@Column
        }
        Button(onClick = onLessons, modifier = Modifier.padding(16.dp)) { Text("Learn new grammar") }
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
                headlineContent = { Text(p.point.title, style = MaterialTheme.typography.titleMedium.japanese()) },
                supportingContent = { Text(p.point.meaning) },
                trailingContent = { Text(p.stage?.label ?: "", style = MaterialTheme.typography.labelSmall) },
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GrammarPointContent(d.point, d.examples)
        if (d.stage == null) {
            Button(onClick = { scope.launch { graph.grammar()?.learn(listOf(d.point)); detail = graph.grammar()?.point(id) } }) { Text("Add to reviews") }
        } else {
            Text("In reviews · ${d.stage!!.label}", color = MaterialTheme.colorScheme.tertiary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GrammarPointContent(point: GrammarPoint, examples: List<GrammarExample>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(point.title, style = MaterialTheme.typography.displaySmall.japanese())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tag("N${point.jlpt}")
            if (point.source != ItemSource.VERIFIED) AiBadge()
            point.textbooks.forEach { (book, chapter) -> Tag("$book $chapter") }
        }
        Text(point.meaning, style = MaterialTheme.typography.titleMedium)
        Text(point.structure, style = MaterialTheme.typography.bodyLarge.japanese(), color = MaterialTheme.colorScheme.primary)
        Text(point.nuance, style = MaterialTheme.typography.bodyMedium)
        if (point.mistakes.isNotEmpty()) {
            Text("Watch out", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
            point.mistakes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
        Text("Examples", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        examples.forEach { ex ->
            Column {
                Text(
                    buildAnnotatedString {
                        append(ex.before)
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)) { append(ex.answer) }
                        append(ex.after)
                    },
                    style = MaterialTheme.typography.bodyLarge.japanese(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(ex.english, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ex.isAiGenerated) AiBadge()
                }
            }
        }
        if (examples.any { !it.isAiGenerated }) {
            Text("Example sentences from Tatoeba (CC BY 2.0 FR).", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
            list.isEmpty() -> { Text("No grammar left to learn (or the grammar pack isn't installed)."); Button(onClick = onDone) { Text("OK") } }
            done -> { Text("Added ${list.size} grammar points to reviews.", style = MaterialTheme.typography.titleMedium); Button(onClick = onDone) { Text("Done") } }
            else -> {
                Text("Grammar ${index + 1} of ${list.size}", style = MaterialTheme.typography.labelLarge)
                GrammarPointContent(list[index], examples)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index > 0) OutlinedButton(onClick = { index-- }) { Text("Back") }
                    Button(onClick = {
                        if (index < list.lastIndex) index++ else scope.launch { graph.grammar()?.learn(list); done = true }
                    }) { Text(if (index < list.lastIndex) "Next" else "Add to reviews") }
                }
            }
        }
    }
}

/** Sentence building: tap chunks in order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildAnswer(exercise: GrammarExercise, resetKey: Any, onSubmit: (String) -> Unit) {
    var picked by remember(resetKey) { mutableStateOf(listOf<Int>()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            picked.joinToString("") { exercise.chunks[it] }.ifEmpty { "Tap the pieces in order" },
            style = MaterialTheme.typography.headlineSmall.japanese(),
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            exercise.chunks.forEachIndexed { i, chunk ->
                if (i !in picked) AssistChip(onClick = { picked = picked + i }, label = { Text(chunk, style = MaterialTheme.typography.titleMedium.japanese()) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picked = picked.dropLast(1) }, enabled = picked.isNotEmpty()) { Text("Undo") }
            Button(onClick = { onSubmit(picked.joinToString("") { exercise.chunks[it] }) }, enabled = picked.size == exercise.chunks.size) { Text("Check") }
        }
    }
}
