package app.tsumugi.android.features.exams

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.prompts.JlptExplainItem
import app.tsumugi.android.features.practice.AiBadge
import app.tsumugi.android.features.practice.Notice
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.japanese
import app.tsumugi.exam.AttemptReview
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.ExamPassage
import app.tsumugi.exam.ReviewedItem
import kotlinx.coroutines.launch

/** A past attempt: scores, then every item with your answer, the key and the explanation (BRIEF §5.11 review mode). */
@Composable
fun AttemptReviewScreen(id: String, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    var review by remember { mutableStateOf<AttemptReview?>(null) }
    var transcript by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(id) {
        val exams = graph.exams()
        review = exams.attempt(id)
        if (review?.summary?.exam == ExamKind.OPI) transcript = exams.opiTranscript(id)
        loaded = true
    }
    val r = review
    when {
        !loaded -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
        r == null -> Text("This attempt couldn't be found.", Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ScoringView(r.summary.exam, r.summary.summary, r.summary.scoring) }
            if (r.summary.exam == ExamKind.OPI) {
                item { Text("Transcript", style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(transcript) { _, (speaker, text) ->
                    Text(
                        (if (speaker == "LEARNER") "You: " else "Interviewer: ") + text,
                        style = MaterialTheme.typography.bodyMedium.japanese(),
                        color = if (speaker == "LEARNER") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                if (r.items.isEmpty()) item { Text("The items of this attempt are no longer installed (the bank was removed).") }
                itemsIndexed(r.items, key = { _, it -> it.item.id }) { i, reviewed ->
                    ReviewedItemCard(i + 1, reviewed, reviewed.item.passageId?.let { r.passages[it] }, onOpenAiSettings)
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ReviewedItemCard(number: Int, reviewed: ReviewedItem, passage: ExamPassage?, onOpenAiSettings: () -> Unit) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val item = reviewed.item
    val chosen = reviewed.answer.choice
    var showPassage by remember { mutableStateOf(false) }
    var explaining by remember { mutableStateOf(false) }
    var explanation by remember { mutableStateOf<AiResult<JlptExplainItem.Output>?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text("$number. ${typeLabel(item.type)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge.japanese())
                Text(if (reviewed.answer.correct) "✓" else "✗", color = if (reviewed.answer.correct) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
            }
            if (passage != null) {
                TextButton(onClick = { showPassage = !showPassage }) { Text(if (showPassage) "Hide passage" else "Show passage") }
                if (showPassage) {
                    if (passage.body.isNotBlank()) ExamText(passage.body, style = MaterialTheme.typography.bodyMedium, highlight = markerOf(item.stem))
                    if (passage.script.isNotEmpty()) Text(passage.script.joinToString("\n") { "${it.speaker}: ${it.text}" }, style = MaterialTheme.typography.bodyMedium.japanese())
                    if (passage.aiGenerated) AiBadge()
                }
            }
            if (item.script.isNotEmpty()) {
                Text("Audio script", style = MaterialTheme.typography.labelMedium)
                Text(item.script.joinToString("\n") { (if (it.speaker.isNotBlank()) "${it.speaker}: " else "") + it.text }, style = MaterialTheme.typography.bodyMedium.japanese())
            }
            ExamText(item.stem, style = MaterialTheme.typography.titleSmall)
            if (item.aiGenerated) AiBadge()
            item.choices.forEachIndexed { i, c ->
                val mark = when {
                    i == item.answer && i == chosen -> "✓ your answer · key"
                    i == item.answer -> "key"
                    i == chosen -> "your answer"
                    else -> ""
                }
                Row {
                    ExamText(
                        "${i + 1}. $c", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = when {
                                i == item.answer -> Color(0xFF2E7D32)
                                i == chosen -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        ),
                    )
                    if (mark.isNotEmpty()) Text(mark, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (chosen == null) Text("Not answered", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            if (item.explanation.isNotBlank()) {
                ExamText(item.explanation, style = MaterialTheme.typography.bodyMedium)
                if (item.aiGenerated) AiBadge()
            } else {
                Text("No written explanation for this item.", style = MaterialTheme.typography.bodySmall)
            }
            when (val e = explanation) {
                null -> OutlinedButton(
                    enabled = !explaining,
                    onClick = {
                        explaining = true
                        scope.launch {
                            val stimulus = listOfNotNull(
                                passage?.body?.takeIf { it.isNotBlank() },
                                (item.script.ifEmpty { passage?.script.orEmpty() }).takeIf { it.isNotEmpty() }?.joinToString("\n") { it.text },
                            ).joinToString("\n\n").ifBlank { null }
                            val level = if (item.exam == ExamKind.JLPT) "JLPT ${item.level}" else "ILR ${item.level}"
                            explanation = graph.ai.gateway().run(
                                JlptExplainItem(),
                                JlptExplainItem.Input(level, item.stem, item.choices, item.answer, chosen, stimulus),
                            )
                            explaining = false
                        }
                    },
                ) { Text(if (explaining) "Asking the model…" else "Explain with AI") }
                is AiResult.Ok -> ExplanationView(e.value, e.engine)
                is AiResult.Fallback -> ExplanationView(e.value, null)
                is AiResult.Unavailable -> Notice("No AI explanation: ${e.reason}", actionLabel = "Open AI settings", onAction = onOpenAiSettings)
            }
        }
    }
}

@Composable
private fun ExplanationView(out: JlptExplainItem.Output, engine: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (engine != null) AiBadge(engine)
        Text(out.explanation, style = MaterialTheme.typography.bodyMedium.japanese())
        if (out.whyWrong.isNotBlank()) Text("Why your choice doesn't fit: ${out.whyWrong}", style = MaterialTheme.typography.bodyMedium.japanese())
        Text("Key point: ${out.keyPoint}", style = MaterialTheme.typography.bodyMedium.japanese())
    }
}
