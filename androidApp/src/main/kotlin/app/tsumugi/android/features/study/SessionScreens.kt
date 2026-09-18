package app.tsumugi.android.features.study

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
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.srs.PathItemDetail

private val Correct = Color(0xFF2E7D32)
private val Wrong = Color(0xFFC62828)

@Composable
fun ReviewScreen(onDone: () -> Unit) {
    val vm: ReviewViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val s = state) {
            null -> Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
            is ReviewState.Asking -> {
                Progress(s.done, s.remaining + 1)
                Text(s.prompt.label + if (s.prompt.practice) " · practice" else "", style = MaterialTheme.typography.labelLarge)
                ItemGlyph(s.prompt.question, s.prompt.item.kind)
                if (s.prompt.mode == AnswerMode.SELF_GRADED) {
                    Button(onClick = vm::reveal, Modifier.fillMaxWidth()) { Text("Show answer") }
                } else {
                    AnswerField(s.prompt.mode, enabled = true, resetKey = s.prompt.card.id + s.done + s.prompt.practice) { vm.submit(it) }
                    s.hint?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!s.wrappingUp) TextButton(onClick = vm::wrapUp) { Text("Wrap up") }
                    TextButton(onClick = vm::finish) { Text("End session") }
                }
            }
            is ReviewState.Revealed -> {
                Progress(s.done, s.remaining + 1)
                ItemGlyph(s.prompt.question, s.prompt.item.kind)
                Text(s.prompt.expected.joinToString("; "), style = MaterialTheme.typography.headlineSmall.japanese(), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                s.prompt.item.reading?.let { Text(it, style = MaterialTheme.typography.titleLarge.japanese(), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Rating.entries.forEach { r ->
                        OutlinedButton(onClick = { vm.grade(r) }, Modifier.weight(1f)) { Text(r.name.lowercase().replaceFirstChar { it.uppercase() }) }
                    }
                }
            }
            is ReviewState.Answered -> {
                Progress(s.done, s.remaining)
                Text(s.prompt.label, style = MaterialTheme.typography.labelLarge)
                ItemGlyph(s.prompt.question, s.prompt.item.kind)
                val color = if (s.correct) Correct else Wrong
                Text(
                    when (s.verdict) {
                        Verdict.CORRECT -> "Correct"
                        Verdict.CLOSE -> "Close enough — “${s.matched}”"
                        else -> "Not quite"
                    },
                    color = color, style = MaterialTheme.typography.titleLarge,
                )
                Text("You answered: ${s.given}", style = MaterialTheme.typography.bodyLarge.japanese())
                Text("Accepted: " + s.prompt.expected.joinToString(", "), style = MaterialTheme.typography.bodyLarge.japanese())
                if (s.prompt.item.myStory.isNotBlank()) Text("My story: " + s.prompt.item.myStory, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::next) { Text("Next") }
                    if (s.canUndo) OutlinedButton(onClick = vm::undo) { Text("Undo") }
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
        LinearProgressIndicator(progress = { done.toFloat() / all }, modifier = Modifier.fillMaxWidth())
        Text("$done done · $total left", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SummaryView(summary: ReviewSummary, onDone: () -> Unit) {
    if (summary.reviewed == 0) {
        Text("No reviews due right now.", style = MaterialTheme.typography.titleMedium)
    } else {
        Text("Session complete", style = MaterialTheme.typography.headlineSmall)
        Text("${summary.correct} / ${summary.reviewed} correct (${(summary.accuracy * 100).toInt()}%)", style = MaterialTheme.typography.titleMedium)
        summary.byKind.forEach { (kind, t) -> Text("${kind.label}: ${t.correct}/${t.total}") }
        if (summary.missed.isNotEmpty()) {
            Text("Missed", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(summary.missed.joinToString("、") { it.primaryText }, style = MaterialTheme.typography.titleLarge.japanese())
        }
        if (summary.leeches.isNotEmpty()) {
            Text("Leeches", style = MaterialTheme.typography.titleSmall, color = Wrong)
            Text(
                "These keep slipping: ${summary.leeches.joinToString("、") { it.primaryText }}. Try rewriting their stories.",
                style = MaterialTheme.typography.bodyMedium.japanese(),
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(onClick = onDone) { Text("Done") }
}

@Composable
fun LessonScreen(onDone: () -> Unit, onOpenItem: (String) -> Unit) {
    val vm: LessonViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val empty by vm.empty.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (val s = state) {
            null -> if (empty) {
                Text("No lessons available. Keep reviewing — new items unlock as earlier ones reach Guru.", style = MaterialTheme.typography.titleMedium)
                Button(onClick = onDone) { Text("OK") }
            } else {
                Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
            }
            is LessonState.Presenting -> {
                Text("Lesson ${s.index + 1} of ${s.items.size}", style = MaterialTheme.typography.labelLarge)
                val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
                var detail by remember(s.item.id) { mutableStateOf<PathItemDetail?>(null) }
                LaunchedEffect(s.item.id) { detail = graph.path()?.detail(s.item.id) }
                detail?.let { PathItemContent(it, onSaveStory = { story -> vm.saveMyStory(s.item.id, story) }, onOpenItem = onOpenItem) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.index > 0) OutlinedButton(onClick = { vm.previousItem() }) { Text("Back") }
                    Button(onClick = { vm.nextItem() }) { Text(if (s.isLast) "Start quiz" else "Next") }
                }
            }
            is LessonState.Quizzing -> {
                Text("Quiz · ${s.remaining + 1} left", style = MaterialTheme.typography.labelLarge)
                Text("${s.question.item.kind.label} · ${if (s.question.mode == AnswerMode.READING) "Reading" else "Meaning"}")
                ItemGlyph(s.question.item.display, s.question.item.kind)
                AnswerField(s.question.mode, enabled = true, resetKey = s.question.toString() + s.remaining) { vm.submit(it) }
                s.hint?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                TextButton(onClick = { vm.reviewItem() }) { Text("Look at the lesson again") }
            }
            is LessonState.QuizFeedback -> {
                ItemGlyph(s.question.item.display, s.question.item.kind)
                Text(if (s.correct) "Correct" else "Not quite", color = if (s.correct) Correct else Wrong, style = MaterialTheme.typography.titleLarge)
                Text("Accepted: " + s.question.expected.joinToString(", "), style = MaterialTheme.typography.bodyLarge.japanese())
                Button(onClick = { vm.next() }) { Text("Next") }
            }
            is LessonState.Complete -> {
                Text("Lessons done", style = MaterialTheme.typography.headlineSmall)
                Text("${s.items.size} items added. Their first reviews are due in 10 minutes.", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDone) { Text("Done") }
            }
        }
    }
}
