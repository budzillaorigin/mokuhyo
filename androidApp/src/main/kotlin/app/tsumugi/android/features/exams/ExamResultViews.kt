package app.tsumugi.android.features.exams

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
import app.tsumugi.android.features.practice.EXAM_DISCLAIMER
import app.tsumugi.android.features.practice.percent
import app.tsumugi.exam.AttemptScoring
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.jlpt.JlptItemType

private fun groupLabel(group: String) = when (group) {
    "language" -> "Language knowledge"
    "reading" -> "Reading"
    "listening" -> "Listening"
    "language_reading" -> "Language knowledge + Reading"
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
        Text(EXAM_DISCLAIMER, style = MaterialTheme.typography.bodySmall)
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
                        "${g.scaled} / ${g.scaledMax} " + if (g.metMinimum) "✓" else "✗ below minimum",
                        color = if (g.metMinimum) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    )
                }
                ScoreBar(g.scaled, g.scaledMax, g.minimum)
                Text("${g.correct} / ${g.administered} correct · minimum ${g.minimum}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (s.total != null) {
            Text(
                "Total ${s.total} / ${s.totalMax ?: 180}" + (s.passMark?.let { " · pass mark $it" } ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        when {
            !s.complete -> Text("Partial form (a drill, or the bank was short), so scaled scores are only indicative.", style = MaterialTheme.typography.bodySmall)
            s.passed == true -> Text("Pass", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF2E7D32))
            s.passed == false -> Text("Not yet", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        }
        Text("Scaled scores use a documented linear approximation, not the official equating.", style = MaterialTheme.typography.bodySmall)
    }
    TallyTable("By item type", s.byType.map { typeLabel(it.key) to it })
    WeakAreas(s.weakAreas.map(::typeLabel))
    if (s.meanTimeMs > 0) Text("Mean time per item: ${s.meanTimeMs / 1000} s", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun DlptScores(s: AttemptScoring) {
    val text = when {
        s.ilr != null && s.ilrConfident -> "Estimated ILR ${s.ilr}"
        s.ilr != null -> "Estimated ILR ${s.ilr} (low confidence: few items per level on this form)"
        s.ilrProvisional != null -> "Provisional ≈ ILR ${s.ilrProvisional} (not enough items for a sustained estimate)"
        else -> "Below ILR 0+ on this form"
    }
    Text(text, style = MaterialTheme.typography.titleMedium)
    Text(
        "Rule: the highest level with ≥ 70% correct, sustained over ≥ 20 items, with consistent performance below it.",
        style = MaterialTheme.typography.bodySmall,
    )
    TallyTable("By ILR level", s.byLevel.map { "ILR ${it.key}" to it })
    TallyTable("By text type", s.byType.map { it.key.replace('_', ' ') to it })
    if (s.meanTimeMs > 0) Text("Mean time per item: ${s.meanTimeMs / 1000} s", style = MaterialTheme.typography.bodyMedium)
    WeakAreas(s.weakAreas.map { it.replace('_', ' ') })
}

@Composable
private fun OpiScores(s: AttemptScoring) {
    Text(s.ilr?.let { "ILR $it" } ?: "Not rated", style = MaterialTheme.typography.titleMedium)
    s.byType.forEach { Text("${it.key.replaceFirstChar { c -> c.uppercase() }}: ${it.correct} / ${it.total}") }
    if (s.weakAreas.isNotEmpty()) Text("Next steps: " + s.weakAreas.joinToString("; "), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TallyTable(title: String, rows: List<Pair<String, AttemptScoring.Tally>>) {
    if (rows.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall)
    rows.forEach { (label, t) ->
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${t.correct}/${t.total}", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
            Text(if (t.total == 0) "–" else percent(t.correct.toDouble() / t.total), Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun WeakAreas(areas: List<String>) {
    if (areas.isNotEmpty()) Text("Work on: " + areas.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
}

/** A horizontal score bar with a tick at the sectional minimum. */
@Composable
private fun ScoreBar(value: Int, max: Int, minimum: Int) {
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.error
    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        if (max > 0) {
            drawRoundRect(fill, size = Size(size.width * value / max, size.height), cornerRadius = r)
            val x = size.width * minimum / max
            drawLine(tick, Offset(x, -2f), Offset(x, size.height + 2f), strokeWidth = 3.dp.toPx())
        }
    }
}
