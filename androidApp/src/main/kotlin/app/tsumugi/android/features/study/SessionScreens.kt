package app.tsumugi.android.features.study

import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import app.tsumugi.android.ui.localized
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.tsumugi.android.ui.japanese
import app.tsumugi.srs.Rating
import app.tsumugi.srs.Verdict
import app.tsumugi.study.AnswerMode
import app.tsumugi.study.LessonState
import app.tsumugi.study.ReviewState
import app.tsumugi.study.ReviewSummary
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.srs.PathItemDetail
import app.tsumugi.jp.strokes.RawResult
import app.tsumugi.jp.strokes.Point
import app.tsumugi.android.features.writing.WritingCanvas
import app.tsumugi.android.ui.StrokeOrderView
import app.tsumugi.dictionary.KanjiStroke
import androidx.compose.foundation.layout.size

private val Correct = Color(0xFF2E7D32)
private val Wrong = Color(0xFFC62828)

/** "Label: value" with the value read in Japanese; wraps instead of clipping at large font sizes. */
@Composable
private fun LabeledJa(label: String, value: String) {
    Text(
        buildAnnotatedString {
            append(label)
            append(" ")
            append(ja(value))
        },
        style = MaterialTheme.typography.bodyLarge.japanese(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(onDone: () -> Unit) {
    val vm: ReviewViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var writingResult by remember { mutableStateOf<RawResult?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val s = state) {
            null -> Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
            is ReviewState.Asking -> {
                Progress(s.done, s.remaining + 1)
                Text(s.prompt.label + if (s.prompt.practice) stringResource(R.string.review_practice_suffix) else "", style = MaterialTheme.typography.labelLarge)
                val exercise = s.prompt.exercise
                if (exercise != null) {
                    JaText(s.prompt.question, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    s.prompt.hint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    ItemGlyph(s.prompt.question, s.prompt.item.kind)
                }
                if (s.prompt.mode == AnswerMode.WRITING) {
                    WritingAnswer(s.prompt.item.primaryText, resetKey = s.prompt.card.id + s.done) { result ->
                        writingResult = result
                        vm.reveal()
                    }
                } else if (s.prompt.mode == AnswerMode.SELF_GRADED) {
                    Button(onClick = vm::reveal, Modifier.fillMaxWidth()) { Text(stringResource(R.string.review_show_answer)) }
                } else if (s.prompt.mode == AnswerMode.BUILD && exercise != null) {
                    BuildAnswer(exercise, resetKey = s.prompt.card.id + s.done + s.prompt.practice) { vm.submit(it) }
                } else {
                    AnswerField(
                        if (s.prompt.mode == AnswerMode.CLOZE) AnswerMode.READING else s.prompt.mode,
                        enabled = true, resetKey = s.prompt.card.id + s.done + s.prompt.practice,
                    ) { vm.submit(it) }
                    s.hint?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!s.wrappingUp) TextButton(onClick = vm::wrapUp) { Text(stringResource(R.string.review_wrap_up)) }
                    TextButton(onClick = vm::finish) { Text(stringResource(R.string.review_end_session)) }
                }
            }
            is ReviewState.Revealed -> {
                Progress(s.done, s.remaining + 1)
                if (s.prompt.mode == AnswerMode.WRITING) {
                    WritingReveal(s.prompt.item.primaryText, writingResult)
                } else {
                    ItemGlyph(s.prompt.question, s.prompt.item.kind)
                }
                JaText(s.prompt.expected.joinToString("; "), Modifier.fillMaxWidth(), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                s.prompt.item.reading?.let { JaText(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center) }
                // Two rows of two so the labels fit at large font sizes.
                Rating.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { r ->
                            val suggested = s.prompt.mode == AnswerMode.WRITING && writingResult?.suggestedRating == r.value
                            val label = r.localized()
                            if (suggested) {
                                Button(onClick = { vm.grade(r) }, Modifier.weight(1f)) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { vm.grade(r) }, Modifier.weight(1f)) { Text(label) }
                            }
                        }
                    }
                }
            }
            is ReviewState.Answered -> {
                Progress(s.done, s.remaining)
                Text(s.prompt.label, style = MaterialTheme.typography.labelLarge)
                val exercise = s.prompt.exercise
                if (exercise != null) {
                    JaText(exercise.example.japanese, style = MaterialTheme.typography.headlineSmall)
                    Text(exercise.example.english, style = MaterialTheme.typography.bodyMedium)
                    JaText("${exercise.point.title} — ${exercise.point.meaning}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    ItemGlyph(s.prompt.question, s.prompt.item.kind)
                }
                val color = if (s.correct) Correct else Wrong
                Text(
                    when (s.verdict) {
                        Verdict.CORRECT -> stringResource(R.string.review_correct)
                        Verdict.CLOSE -> stringResource(R.string.review_close, s.matched.toString())
                        else -> stringResource(R.string.review_not_quite)
                    },
                    color = color, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LabeledJa(stringResource(R.string.review_you_answered), s.given)
                LabeledJa(stringResource(R.string.review_accepted), s.prompt.expected.joinToString(", "))
                if (s.prompt.item.myStory.isNotBlank()) Text(stringResource(R.string.review_my_story_prefix) + s.prompt.item.myStory, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::next) { Text(stringResource(R.string.action_next)) }
                    if (s.canUndo) OutlinedButton(onClick = vm::undo) { Text(stringResource(R.string.action_undo)) }
                }
            }
            is ReviewState.Finished -> SummaryView(s.summary, onDone)
        }
    }
}

@Composable
private fun Progress(done: Int, total: Int) {
    val all = (done + total).coerceAtLeast(1)
    Column {
        LinearProgressIndicator(progress = { done.toFloat() / all }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
        Text(stringResource(R.string.review_progress, done, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryView(summary: ReviewSummary, onDone: () -> Unit) {
    if (summary.reviewed == 0) {
        Text(stringResource(R.string.review_none_due), style = MaterialTheme.typography.titleMedium)
    } else {
        Text(stringResource(R.string.review_session_complete), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.review_summary_score, summary.correct, summary.reviewed, (summary.accuracy * 100).toInt()), style = MaterialTheme.typography.titleMedium)
        summary.byKind.forEach { (kind, t) -> Text("${kind.localized()}: ${t.correct}/${t.total}") }
        if (summary.missed.isNotEmpty()) {
            Text(stringResource(R.string.review_missed), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            JaText(summary.missed.joinToString("、") { it.primaryText }, style = MaterialTheme.typography.titleLarge)
        }
        if (summary.leeches.isNotEmpty()) {
            Text(stringResource(R.string.review_leeches), style = MaterialTheme.typography.titleSmall, color = Wrong)
            Text(stringResource(R.string.review_leeches_hint), style = MaterialTheme.typography.bodyMedium)
            JaText(summary.leeches.joinToString("、") { it.primaryText }, style = MaterialTheme.typography.titleMedium)
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
}

@Composable
fun LessonScreen(onDone: () -> Unit, onOpenItem: (String) -> Unit) {
    val vm: LessonViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val empty by vm.empty.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val s = state) {
            null -> if (empty) {
                Text(stringResource(R.string.lesson_none), style = MaterialTheme.typography.titleMedium)
                Button(onClick = onDone) { Text(stringResource(R.string.action_ok)) }
            } else {
                Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
            }
            is LessonState.Presenting -> {
                Text(stringResource(R.string.lesson_progress, s.index + 1, s.items.size), style = MaterialTheme.typography.labelLarge)
                val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
                var detail by remember(s.item.id) { mutableStateOf<PathItemDetail?>(null) }
                LaunchedEffect(s.item.id) { detail = graph.path()?.detail(s.item.id) }
                detail?.let { PathItemContent(it, onSaveStory = { story -> vm.saveMyStory(s.item.id, story) }, onOpenItem = onOpenItem) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.index > 0) OutlinedButton(onClick = { vm.previousItem() }) { Text(stringResource(R.string.action_back)) }
                    Button(onClick = { vm.nextItem() }) { Text(stringResource(if (s.isLast) R.string.lesson_start_quiz else R.string.action_next)) }
                }
            }
            is LessonState.Quizzing -> {
                Text(stringResource(R.string.lesson_quiz_left, s.remaining + 1), style = MaterialTheme.typography.labelLarge)
                Text("${s.question.item.kind.localized()} · ${stringResource(if (s.question.mode == AnswerMode.READING) R.string.lesson_reading else R.string.lesson_meaning)}")
                ItemGlyph(s.question.item.display, s.question.item.kind)
                AnswerField(s.question.mode, enabled = true, resetKey = s.question.toString() + s.remaining) { vm.submit(it) }
                s.hint?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                TextButton(onClick = { vm.reviewItem() }) { Text(stringResource(R.string.lesson_look_again)) }
            }
            is LessonState.QuizFeedback -> {
                ItemGlyph(s.question.item.display, s.question.item.kind)
                Text(
                    stringResource(if (s.correct) R.string.review_correct else R.string.review_not_quite),
                    color = if (s.correct) Correct else Wrong, style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                LabeledJa(stringResource(R.string.review_accepted), s.question.expected.joinToString(", "))
                Button(onClick = { vm.next() }) { Text(stringResource(R.string.action_next)) }
            }
            is LessonState.Complete -> {
                Text(stringResource(R.string.lesson_done), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.lesson_done_detail, s.items.size), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

/** Raw writing for a WRITING card: draw from memory, then check (stroke count and order are graded in shared code). */
@Composable
private fun WritingAnswer(kanji: String, resetKey: Any, onChecked: (RawResult?) -> Unit) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var strokes by remember(resetKey) { mutableStateOf<List<List<Point>>>(emptyList()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WritingCanvas(Modifier.fillMaxWidth(), inked = strokes, contentDescription = stringResource(R.string.writing_canvas_description, strokes.size)) { strokes = strokes + listOf(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { strokes = strokes.dropLast(1) }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.action_undo)) }
            Button(onClick = { scope.launch { onChecked(graph.writing()?.checkRaw(kanji, strokes)) } }, enabled = strokes.isNotEmpty()) { Text(stringResource(R.string.action_check)) }
        }
    }
}

@Composable
private fun WritingReveal(kanji: String, result: RawResult?) {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    var strokes by remember(kanji) { mutableStateOf<List<KanjiStroke>>(emptyList()) }
    LaunchedEffect(kanji) { strokes = graph.dictionary()?.strokes(kanji).orEmpty() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (strokes.isNotEmpty()) StrokeOrderView(strokes, Modifier.size(160.dp)) else JaText(kanji, style = MaterialTheme.typography.displayLarge)
        result?.let {
            Text(
                listOf(
                    stringResource(if (it.countOk) R.string.writing_count_ok else R.string.writing_count_wrong),
                    stringResource(if (it.orderOk) R.string.writing_order_ok else R.string.writing_order_wrong),
                ).joinToString(" · ") + stringResource(R.string.writing_suggested, Rating.entries[it.suggestedRating - 1].localized()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
