package app.tsumugi.android.features.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tsumugi.android.R
import app.tsumugi.android.app.Route
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.platform.rememberVoices
import app.tsumugi.android.ui.ErrorState
import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.PlayLabel
import app.tsumugi.android.ui.readable
import app.tsumugi.kana.KanaCourseStatus
import app.tsumugi.kana.KanaLesson
import app.tsumugi.kana.KanaPlacementAnswer
import app.tsumugi.kana.KanaPlacementResult
import app.tsumugi.kana.KanaScript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
private fun scriptName(script: KanaScript) = stringResource(if (script == KanaScript.HIRAGANA) R.string.kana_hiragana else R.string.kana_katakana)

/**
 * The kana course for absolute beginners (G-13, D-117): 15 lessons per script with original mnemonics (AI-drafted,
 * labeled until reviewed), stroke practice on the writing canvas, and a placement check that skips a script.
 */
@Composable
fun KanaCourseScreen(push: (Route) -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<KanaCourseStatus?>(null) }
    var pending by remember { mutableStateOf<Set<String>>(emptySet()) }
    var lessons by remember { mutableStateOf<List<KanaLesson>>(emptyList()) }
    var needed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun load() {
        try {
            val kana = graph.kana()
            lessons = kana.lessons()
            status = kana.status(graph.settings)
            pending = kana.lessonQueue(graph.settings, lessons.size).map { it.id }.toSet()
            needed = kana.needed(graph.settings)
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.readable()
        }
    }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.kana_intro), style = MaterialTheme.typography.bodyMedium)
        error?.let { ErrorState(stringResource(R.string.error_loading, it), onRetry = { scope.launch { load() } }) }
        val s = status ?: run {
            if (error == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        if (s.complete) Text(stringResource(R.string.kana_complete), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (!needed && !s.complete) {
            OutlinedButton(onClick = { scope.launch { graph.kana().enroll(graph.settings); load() } }) { Text(stringResource(R.string.kana_enroll)) }
        }
        KanaScript.entries.forEach { script ->
            val done = if (script == KanaScript.HIRAGANA) s.hiraganaLessonsDone else s.katakanaLessonsDone
            val skipped = if (script == KanaScript.HIRAGANA) s.hiraganaSkipped else s.katakanaSkipped
            Text(scriptName(script), Modifier.padding(top = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Text(
                if (skipped) stringResource(R.string.kana_skipped) else stringResource(R.string.kana_progress, done, s.lessonsPerScript),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { push(Route.KanaPlacement(script)) }) { Text(stringResource(R.string.kana_placement)) }
                TextButton(onClick = { scope.launch { graph.kana().setSkipped(script, !skipped, graph.settings); load() } }) {
                    Text(stringResource(if (skipped) R.string.kana_unskip else R.string.kana_skip))
                }
            }
            lessons.filter { it.script == script }.forEach { lesson ->
                val finished = lesson.id !in pending && !skipped
                ListItem(
                    modifier = Modifier.clickable { push(Route.KanaLesson(lesson.id)) },
                    leadingContent = { Text(if (finished) "✓" else "${lesson.order}", style = MaterialTheme.typography.titleMedium) },
                    headlineContent = { Text(lesson.title) },
                    supportingContent = { JaText(lesson.chars.joinToString(" ") { it.kana }, maxLines = 1) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KanaLessonScreen(id: String, push: (Route) -> Unit, onDone: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val voices = rememberVoices()
    var lesson by remember { mutableStateOf<KanaLesson?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(id) { lesson = graph.kana().lesson(id) }
    val l = lesson ?: return LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(l.title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        Text(l.intro, style = MaterialTheme.typography.bodyMedium)
        if (l.introSource == "llm") AiBadge()
        l.chars.forEach { c ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    JaText(c.kana, Modifier.widthIn(min = 72.dp), style = MaterialTheme.typography.displayMedium, fontSize = 56.sp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(c.romaji.joinToString(" / "), style = MaterialTheme.typography.titleMedium)
                        Text(c.mnemonic, style = MaterialTheme.typography.bodyMedium)
                        if (c.mnemonicSource == "llm") AiBadge()
                        TextButton(onClick = { scope.launch { voices.say(c.kana) } }) { PlayLabel(stringResource(R.string.kana_hear)) }
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { push(Route.WritingPractice(l.chars.flatMap { it.strokeChars }.distinct())) }) { Text(stringResource(R.string.kana_strokes)) }
            Button(onClick = {
                busy = true
                scope.launch {
                    message = try {
                        graph.kana().completeLesson(l, graph.settings)
                        onDone()
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.readable()
                    }
                    busy = false
                }
            }, enabled = !busy) { Text(stringResource(R.string.kana_finish_lesson)) }
        }
        Text(stringResource(R.string.kana_finish_hint), style = MaterialTheme.typography.bodySmall)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** 10 random base kana: type the romaji. 90% or better skips that script (D-117). */
@Composable
fun KanaPlacementScreen(script: KanaScript, onDone: () -> Unit) {
    val graph = rememberGraph()
    val questions = remember(script) { mutableStateListOf<app.tsumugi.kana.KanaChar>() }
    val answers = remember(script) { mutableStateListOf<KanaPlacementAnswer>() }
    var index by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<KanaPlacementResult?>(null) }
    LaunchedEffect(script) {
        questions.clear()
        questions += graph.kana().placementQuestions(script, System.currentTimeMillis())
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.kana_placement_intro, scriptName(script)), style = MaterialTheme.typography.bodyMedium)
        val r = result
        if (r != null) {
            Text(stringResource(R.string.kana_placement_score, r.correct, r.total), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(if (r.passed) R.string.kana_placement_passed else R.string.kana_placement_failed), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
            return@Column
        }
        val q = questions.getOrNull(index)
        if (q == null) {
            if (questions.isNotEmpty()) LaunchedEffect(Unit) { result = graph.kana().gradePlacement(script, answers.toList(), graph.settings) }
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        Text("${index + 1} / ${questions.size}", style = MaterialTheme.typography.labelLarge)
        JaText(q.kana, Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.displayLarge, fontSize = 96.sp)
        fun submit() {
            answers += KanaPlacementAnswer(q, typed)
            typed = ""
            index++
        }
        OutlinedTextField(
            typed, { typed = it.lowercase() }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.kana_type_romaji)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (typed.isNotBlank()) submit() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = ::submit, enabled = typed.isNotBlank()) { Text(stringResource(R.string.action_next)) }
            TextButton(onClick = { typed = ""; submit() }) { Text(stringResource(R.string.kana_dont_know)) }
        }
    }
}
