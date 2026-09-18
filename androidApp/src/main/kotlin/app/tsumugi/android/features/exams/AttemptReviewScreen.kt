package app.tsumugi.android.features.exams

import app.tsumugi.android.ui.JaText
import app.tsumugi.android.ui.ja
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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
        r == null -> Text(stringResource(R.string.attempt_not_found), Modifier.padding(16.dp))
        else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { ScoringView(r.summary.exam, r.summary.summary, r.summary.scoring) }
            if (r.summary.exam == ExamKind.OPI) {
                item { Text(stringResource(R.string.opi_transcript), style = MaterialTheme.typography.titleMedium) }
                itemsIndexed(transcript) { _, (speaker, text) ->
                    Text(
                        buildAnnotatedString {
                            append(stringResource(if (speaker == "LEARNER") R.string.opi_you else R.string.opi_interviewer))
                            append(ja(text))
                        },
                        style = MaterialTheme.typography.bodyMedium.japanese(),
                        color = if (speaker == "LEARNER") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                if (r.items.isEmpty()) item { Text(stringResource(R.string.attempt_items_gone)) }
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
                JaText("$number. ${typeLabel(item.type)}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                val verdict = stringResource(if (reviewed.answer.correct) R.string.review_correct else R.string.review_not_quite)
                Text(if (reviewed.answer.correct) "✓" else "✗", Modifier.semantics { contentDescription = verdict }, color = if (reviewed.answer.correct) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
            }
            if (passage != null) {
                TextButton(onClick = { showPassage = !showPassage }) { Text(stringResource(if (showPassage) R.string.attempt_hide_passage else R.string.attempt_show_passage)) }
                if (showPassage) {
                    if (passage.body.isNotBlank()) ExamText(passage.body, style = MaterialTheme.typography.bodyMedium, highlight = markerOf(item.stem))
                    if (passage.script.isNotEmpty()) JaText(passage.script.joinToString("\n") { "${it.speaker}: ${it.text}" }, style = MaterialTheme.typography.bodyMedium)
                    if (passage.aiGenerated) AiBadge()
                }
            }
            if (item.script.isNotEmpty()) {
                Text(stringResource(R.string.attempt_audio_script), style = MaterialTheme.typography.labelMedium)
                JaText(item.script.joinToString("\n") { (if (it.speaker.isNotBlank()) "${it.speaker}: " else "") + it.text }, style = MaterialTheme.typography.bodyMedium)
            }
            ExamText(item.stem, style = MaterialTheme.typography.titleSmall)
            if (item.aiGenerated) AiBadge()
            item.choices.forEachIndexed { i, c ->
                val mark = when {
                    i == item.answer && i == chosen -> stringResource(R.string.attempt_mark_both)
                    i == item.answer -> stringResource(R.string.attempt_mark_key)
                    i == chosen -> stringResource(R.string.attempt_mark_yours)
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
            if (chosen == null) Text(stringResource(R.string.attempt_not_answered), style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            if (item.explanation.isNotBlank()) {
                ExamText(item.explanation, style = MaterialTheme.typography.bodyMedium)
                if (item.aiGenerated) AiBadge()
            } else {
                Text(stringResource(R.string.attempt_no_explanation), style = MaterialTheme.typography.bodySmall)
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
                ) { Text(stringResource(if (explaining) R.string.attempt_asking else R.string.attempt_explain)) }
                is AiResult.Ok -> ExplanationView(e.value, e.engine)
                is AiResult.Fallback -> ExplanationView(e.value, null)
                is AiResult.Unavailable -> Notice(stringResource(R.string.attempt_no_ai, "${e.reason}"), actionLabel = stringResource(R.string.ai_open_settings), onAction = onOpenAiSettings)
            }
        }
    }
}

@Composable
private fun ExplanationView(out: JlptExplainItem.Output, engine: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (engine != null) AiBadge(engine)
        JaText(out.explanation, style = MaterialTheme.typography.bodyMedium)
        if (out.whyWrong.isNotBlank()) Text(stringResource(R.string.attempt_why_wrong, out.whyWrong), style = MaterialTheme.typography.bodyMedium.japanese())
        Text(stringResource(R.string.attempt_key_point, out.keyPoint), style = MaterialTheme.typography.bodyMedium.japanese())
    }
}
