package app.tsumugi.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.tsumugi.jp.FuriganaSegment
import app.tsumugi.jp.Mora
import app.tsumugi.jp.PitchAccent

/** Text with ruby readings above kanji runs. */
@Composable
fun FuriganaText(
    segments: List<FuriganaSegment>,
    style: TextStyle = MaterialTheme.typography.headlineLarge,
    showFurigana: Boolean = true,
) {
    Row(verticalAlignment = Alignment.Bottom) {
        segments.forEach { seg ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (showFurigana) {
                    Text(
                        seg.rt ?: " ",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (style.fontSize.value * 0.45f).sp).japanese(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(seg.ruby, style = style.japanese())
            }
        }
    }
}

/** Pitch-accent diagram: one dot per mora (plus the following particle), high/low joined by lines. */
@Composable
fun PitchDiagram(reading: String, accent: PitchAccent, modifier: Modifier = Modifier) {
    val morae = Mora.split(reading)
    val heights = accent.heights()
    val color = MaterialTheme.colorScheme.secondary
    val step = 26.dp
    Column(modifier) {
        Canvas(Modifier.size(step * heights.size, 30.dp)) {
            val stepPx = step.toPx()
            val points = heights.mapIndexed { i, high -> Offset(stepPx * i + stepPx / 2, if (high) 6.dp.toPx() else 24.dp.toPx()) }
            for (i in 0 until points.size - 1) drawLine(color, points[i], points[i + 1], strokeWidth = 2.dp.toPx())
            points.forEachIndexed { i, p ->
                if (i == points.lastIndex) drawCircle(color, 4.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                else drawCircle(color, 4.dp.toPx(), p)
            }
        }
        Row {
            morae.forEach { m ->
                Text(m, Modifier.size(step, 22.dp), style = MaterialTheme.typography.bodyMedium.japanese(), maxLines = 1)
            }
        }
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun TagRow(tags: List<String>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) { tags.forEach { Tag(it) } }
}

fun jlptLabel(level: Int?): String? = level?.let { "N$it" }
