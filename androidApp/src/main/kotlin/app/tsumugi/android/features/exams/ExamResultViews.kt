package app.tsumugi.android.features.exams

import app.tsumugi.android.ui.JaText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.tsumugi.android.features.practice.percent
import app.tsumugi.exam.AttemptScoring
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.jlpt.JlptItemType

@Composable
private fun groupLabel(group: String) = when (group) {
    "language" -> stringResource(R.string.exam_group_language)
    "reading" -> stringResource(R.string.exam_group_reading)
    "listening" -> stringResource(R.string.exam_group_listening)
    "language_reading" -> stringResource(R.string.exam_group_language_reading)
    else -> group.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

fun typeLabel(key: String): String = JlptItemType.of(key)?.let { "${it.title} (${it.english})" } ?: key.replace('_', ' ')

/** Scores of one attempt: JLPT scaled scores, a DLPT ILR estimate, or OPI factor ratings. */
@Composable
fun ScoringView(exam: ExamKind, summary: String, scoring: AttemptScoring) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(summary, style = MaterialTheme.typography.titleLarge)
        when {
            exam == ExamKind.JLPT -> JlptScores(scoring)
            exam.isDlpt -> DlptScores(scoring)
            exam == ExamKind.OPI -> OpiScores(scoring)
        }
        Text(stringResource(R.string.exam_disclaimer), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun JlptScores(s: AttemptScoring) {
    if (s.groups.isNotEmpty()) {
        s.groups.forEach { g ->
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(groupLabel(g.group), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${g.scaled} / ${g.scaledMax} " + if (g.metMinimum) "✓" else stringResource(R.string.exam_below_minimum),
                        color = if (g.metMinimum) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
                ScoreBar(g.scaled, g.scaledMax, g.minimum, stringResource(R.string.exam_score_bar_description, groupLabel(g.group), g.scaled, g.scaledMax, g.minimum))
                Text(stringResource(R.string.exam_group_detail, g.correct, g.administered, g.minimum), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (s.total != null) {
            Text(
                stringResource(R.string.exam_total, s.total ?: 0, s.totalMax ?: 180) + (s.passMark?.let { stringResource(R.string.exam_pass_mark, it) } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        when {
            !s.complete -> Text(stringResource(R.string.exam_partial), style = MaterialTheme.typography.bodySmall)
            s.passed == true -> Text(stringResource(R.string.exam_pass), style = MaterialTheme.typography.headlineSmall, color = Color(0xFF2E7D32))
            s.passed == false -> Text(stringResource(R.string.exam_not_yet), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        }
        Text(stringResource(R.string.exam_scaling_note), style = MaterialTheme.typography.bodySmall)
    }
    TallyTable(stringResource(R.string.exam_by_type), s.byType.map { typeLabel(it.key) to it })
    WeakAreas(s.weakAreas.map(::typeLabel))
    if (s.meanTimeMs > 0) Text(stringResource(R.string.exam_mean_time, (s.meanTimeMs / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DlptScores(s: AttemptScoring) {
    val text = when {
        s.ilr != null && s.ilrConfident -> stringResource(R.string.exam_ilr_estimate, s.ilr.toString())
        s.ilr != null -> stringResource(R.string.exam_ilr_low_confidence, s.ilr.toString())
        s.ilrProvisional != null -> stringResource(R.string.exam_ilr_provisional, s.ilrProvisional.toString())
        else -> stringResource(R.string.exam_ilr_below)
    }
    Text(text, style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.exam_ilr_rule),
        style = MaterialTheme.typography.bodySmall,
    )
    TallyTable(stringResource(R.string.exam_by_level), s.byLevel.map { "ILR ${it.key}" to it })
    TallyTable(stringResource(R.string.exam_by_text_type), s.byType.map { it.key.replace('_', ' ') to it })
    if (s.meanTimeMs > 0) Text(stringResource(R.string.exam_mean_time, (s.meanTimeMs / 1000).toInt()), style = MaterialTheme.typography.bodyMedium)
    WeakAreas(s.weakAreas.map { it.replace('_', ' ') })
}

@Composable
private fun OpiScores(s: AttemptScoring) {
    Text(s.ilr?.let { "ILR $it" } ?: stringResource(R.string.opi_not_rated), style = MaterialTheme.typography.titleMedium)
    s.byType.forEach { Text("${it.key.replaceFirstChar { c -> c.uppercase() }}: ${it.correct} / ${it.total}") }
    if (s.weakAreas.isNotEmpty()) Text(stringResource(R.string.opi_next_steps) + s.weakAreas.joinToString("; "), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TallyTable(title: String, rows: List<Pair<String, AttemptScoring.Tally>>) {
    if (rows.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall)
    rows.forEach { (label, t) ->
        Row(Modifier.fillMaxWidth()) {
            JaText(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${t.correct}/${t.total}", Modifier.widthIn(min = 56.dp), style = MaterialTheme.typography.bodyMedium)
            Text(if (t.total == 0) "–" else percent(t.correct.toDouble() / t.total), Modifier.widthIn(min = 48.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WeakAreas(areas: List<String>) {
    if (areas.isNotEmpty()) Text(stringResource(R.string.exam_work_on) + areas.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
}

/** A horizontal score bar with a tick at the sectional minimum. */
@Composable
private fun ScoreBar(value: Int, max: Int, minimum: Int, description: String) {
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = description }) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (max > 0) {
            drawRoundRect(fill, size = Size(size.width * value / max, size.height), cornerRadius = r)
            val x = size.width * minimum / max
            drawLine(tick, Offset(x, -2f), Offset(x, size.height + 2f), strokeWidth = 3.dp.toPx())
        }
    }
}
