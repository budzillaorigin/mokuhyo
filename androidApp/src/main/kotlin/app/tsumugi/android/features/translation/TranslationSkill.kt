package app.tsumugi.android.features.translation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.tsumugi.android.R
import app.tsumugi.android.features.practice.rememberGraph
import app.tsumugi.android.ui.Tag
import app.tsumugi.translation.TranslationDirection
import app.tsumugi.translation.TranslationSkillLine

/**
 * The translation skill line on Me (§6.12, D-274, D-294): the daily averages as a line on a Canvas (0–100), recent
 * averages per direction and genre, and the trend, all computed by `TranslationService.skillLine()`. The chart has a
 * spoken summary for TalkBack. Nothing is drawn without attempts; the card then points to the workbench.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TranslationSkillCard(onOpenWorkbench: () -> Unit, onOpenHistory: () -> Unit) {
    val graph = rememberGraph()
    var line by remember { mutableStateOf<TranslationSkillLine?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { graph.translationWorkbench.skillLine() }.onSuccess { line = it }.onFailure { failed = true }
    }
    // A failed load hides the card (Me stays usable); the workbench is still on Practice.
    if (failed) return
    val l = line ?: return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.tw_skill_title), style = MaterialTheme.typography.titleSmall)
            if (l.attempts == 0) {
                Text(stringResource(R.string.tw_skill_empty), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onOpenWorkbench) { Text(stringResource(R.string.tw_skill_open)) }
                return@Column
            }
            val scores = l.daily.map { it.second }
            val summary = stringResource(
                R.string.tw_skill_chart, scores.size, scores.minOrNull() ?: 0, scores.maxOrNull() ?: 0, scores.lastOrNull() ?: 0,
            )
            SkillChart(scores, Modifier.fillMaxWidth().height(96.dp).clearAndSetSemantics { contentDescription = summary })
            Text(stringResource(R.string.tw_skill_attempts, l.attempts), style = MaterialTheme.typography.bodyMedium)
            val recent = TranslationDirection.entries.mapNotNull { d -> l.recentByDirection[d]?.let { "${directionLabel(d)} $it" } }
            if (recent.isNotEmpty()) Text(stringResource(R.string.tw_skill_recent, recent.joinToString(" · ")), style = MaterialTheme.typography.bodySmall)
            l.trend?.let { t ->
                Text(
                    when {
                        t > 0 -> stringResource(R.string.tw_skill_trend_up, t)
                        t < 0 -> stringResource(R.string.tw_skill_trend_down, t)
                        else -> stringResource(R.string.tw_skill_trend_flat)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (l.recentByGenre.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    l.recentByGenre.entries.sortedBy { it.key }.forEach { (g, v) -> Tag("${genreLabel(g)} $v") }
                }
            }
            if (l.points.any { it.aiGraded } && l.points.any { !it.aiGraded }) {
                Text(stringResource(R.string.tw_skill_ai_note), style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenHistory) { Text(stringResource(R.string.tw_history)) }
                TextButton(onClick = onOpenWorkbench) { Text(stringResource(R.string.tw_skill_open)) }
            }
        }
    }
}

/** A 0–100 line with guides at 50 and 100; one point draws as a dot. */
@Composable
private fun SkillChart(scores: List<Int>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        listOf(0f, 0.5f, 1f).forEach { f -> drawLine(guide, Offset(0f, h * (1 - f)), Offset(w, h * (1 - f)), 1.dp.toPx(), pathEffect = dash) }
        if (scores.isEmpty()) return@Canvas
        fun point(i: Int): Offset {
            val x = if (scores.size == 1) w / 2 else w * i / (scores.size - 1)
            return Offset(x, h * (1 - scores[i].coerceIn(0, 100) / 100f))
        }
        if (scores.size > 1) {
            val path = Path().apply {
                moveTo(point(0).x, point(0).y)
                for (i in 1 until scores.size) lineTo(point(i).x, point(i).y)
            }
            drawPath(path, line, style = Stroke(width = 2.5.dp.toPx()))
        }
        scores.indices.forEach { drawCircle(line, radius = 3.dp.toPx(), center = point(it)) }
    }
}
