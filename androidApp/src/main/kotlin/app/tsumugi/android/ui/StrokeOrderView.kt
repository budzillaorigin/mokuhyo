package app.tsumugi.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.tsumugi.android.R
import app.tsumugi.dictionary.KanjiStroke
import app.tsumugi.jp.strokes.Point
import app.tsumugi.jp.strokes.SvgPath

private const val KANJIVG_SIZE = 109f

/**
 * Animated KanjiVG stroke order: completed strokes in the foreground colour, the current stroke drawn
 * progressively with a start dot. Tap to replay. With "Remove animations" on, the finished character is shown
 * straight away (every stroke drawn, no motion).
 */
@Composable
fun StrokeOrderView(strokes: List<KanjiStroke>, modifier: Modifier = Modifier, msPerStroke: Int = 550, contentDescription: String? = null) {
    val polylines = remember(strokes) { strokes.map { SvgPath.flatten(it.path) } }
    val progress = remember(strokes) { Animatable(0f) }
    var replay by remember { mutableIntStateOf(0) }
    val still = rememberAnimationsDisabled()
    LaunchedEffect(strokes, replay) {
        if (still) {
            progress.snapTo(polylines.size.toFloat())
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        progress.animateTo(polylines.size.toFloat(), tween(msPerStroke * polylines.size, easing = LinearEasing))
    }
    val replayLabel = stringResource(R.string.stroke_replay)
    val ink = MaterialTheme.colorScheme.onSurface
    val guide = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.secondary

    Canvas(
        modifier
            .aspectRatio(1f)
            .border(1.dp, guide)
            .clickable(onClickLabel = replayLabel) { replay++ }
            .semantics { this.contentDescription = contentDescription ?: replayLabel },
    ) {
        val scale = size.minDimension / KANJIVG_SIZE
        val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        drawLine(guide, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), pathEffect = dash)
        drawLine(guide, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), pathEffect = dash)

        val done = progress.value.toInt()
        val partial = progress.value - done
        polylines.forEachIndexed { i, pts ->
            when {
                i < done -> drawPolyline(pts, pts.size, scale, ink)
                i == done && partial > 0f -> {
                    val n = (pts.size * partial).toInt().coerceAtLeast(1)
                    drawPolyline(pts, n, scale, accent)
                    drawCircle(accent, 3.5f * scale, pts.first().toOffset(scale))
                }
                else -> drawPolyline(pts, pts.size, scale, guide)
            }
        }
    }
}

private fun Point.toOffset(scale: Float) = Offset(x * scale, y * scale)

private fun DrawScope.drawPolyline(points: List<Point>, count: Int, scale: Float, color: androidx.compose.ui.graphics.Color) {
    if (points.isEmpty()) return
    val path = Path().apply {
        moveTo(points[0].x * scale, points[0].y * scale)
        for (k in 1 until count.coerceAtMost(points.size)) lineTo(points[k].x * scale, points[k].y * scale)
    }
    drawPath(path, color, style = Stroke(width = 4f * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
