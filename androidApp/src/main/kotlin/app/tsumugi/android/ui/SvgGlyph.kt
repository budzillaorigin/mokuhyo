package app.tsumugi.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser

/**
 * The onomatopoeia theme glyphs (D-236): original 64×64 line drawings in `currentColor`. This is deliberately not an
 * SVG engine. It reads only `<path d>`, `<circle cx cy r>` and `<rect x y width height rx>` plus the root's stroke
 * width, caps, joins, dash arrays and `rotate(…)`, and strokes them in [color]. Everything else (scripts, links, styles,
 * other transforms) is ignored, oversized input draws nothing, and a parse failure draws nothing rather than throwing.
 */
@Composable
fun SvgGlyph(svg: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val glyph = remember(svg) { runCatching { SvgGlyphParser.parse(svg) }.getOrNull() }
    Canvas(modifier) {
        val g = glyph ?: return@Canvas
        val s = minOf(size.width, size.height) / SvgGlyphParser.VIEWBOX
        val dx = (size.width - SvgGlyphParser.VIEWBOX * s) / 2
        val dy = (size.height - SvgGlyphParser.VIEWBOX * s) / 2
        translateAndScale(dx, dy, s) {
            g.shapes.forEach { shape ->
                val stroke = Stroke(
                    width = g.strokeWidth,
                    cap = g.cap,
                    join = g.join,
                    pathEffect = (shape.dash ?: g.dash)?.let { PathEffect.dashPathEffect(it) },
                )
                runCatching {
                    val (angle, px, py) = shape.rotate ?: Triple(0f, 0f, 0f)
                    rotate(angle, Offset(px, py)) {
                        when (shape) {
                            is GlyphShape.P -> drawPath(shape.path, color, style = stroke)
                            is GlyphShape.C -> drawCircle(color, shape.r, Offset(shape.cx, shape.cy), style = stroke)
                            is GlyphShape.R -> drawRoundRect(color, Offset(shape.x, shape.y), Size(shape.w, shape.h), CornerRadius(shape.rx, shape.rx), style = stroke)
                        }
                    }
                }
            }
        }
    }
}

private inline fun androidx.compose.ui.graphics.drawscope.DrawScope.translateAndScale(
    dx: Float,
    dy: Float,
    s: Float,
    crossinline block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit,
) {
    drawContext.transform.translate(dx, dy)
    scale(s, s, pivot = Offset.Zero) { block() }
    drawContext.transform.translate(-dx, -dy)
}

internal sealed class GlyphShape {
    /** Per-element dash array and `rotate(a cx cy)` (the only transform the glyphs use; others are ignored). */
    var dash: FloatArray? = null
    var rotate: Triple<Float, Float, Float>? = null

    class P(val path: Path) : GlyphShape()
    class C(val cx: Float, val cy: Float, val r: Float) : GlyphShape()
    class R(val x: Float, val y: Float, val w: Float, val h: Float, val rx: Float) : GlyphShape()
}

internal class Glyph(val shapes: List<GlyphShape>, val strokeWidth: Float, val cap: StrokeCap, val join: StrokeJoin, val dash: FloatArray?)

internal object SvgGlyphParser {
    const val VIEWBOX = 64f
    private const val MAX_BYTES = 16 * 1024
    private const val MAX_SHAPES = 64
    private val TAG = Regex("<(path|circle|rect|svg)\\b([^>]*)>", RegexOption.IGNORE_CASE)
    private val ATTR = Regex("([a-zA-Z-]+)\\s*=\\s*\"([^\"]*)\"")

    fun parse(svg: String): Glyph? {
        if (svg.length > MAX_BYTES) return null
        var width = 3f
        var cap = StrokeCap.Round
        var join = StrokeJoin.Round
        var rootDash: FloatArray? = null
        val shapes = mutableListOf<GlyphShape>()
        for (m in TAG.findAll(svg)) {
            if (shapes.size >= MAX_SHAPES) break
            val attrs = ATTR.findAll(m.groupValues[2]).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            fun num(name: String, default: Float = 0f) = attrs[name]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
            val before = shapes.size
            when (m.groupValues[1].lowercase()) {
                "svg" -> {
                    width = num("stroke-width", 3f).coerceIn(0.5f, 12f)
                    cap = when (attrs["stroke-linecap"]) { "butt" -> StrokeCap.Butt; "square" -> StrokeCap.Square; else -> StrokeCap.Round }
                    join = when (attrs["stroke-linejoin"]) { "miter" -> StrokeJoin.Miter; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Round }
                    rootDash = dash(attrs["stroke-dasharray"])
                }
                "path" -> {
                    val d = attrs["d"]?.takeIf { it.length <= 4096 } ?: continue
                    runCatching { PathParser().parsePathString(d).toPath() }.getOrNull()?.let { shapes += GlyphShape.P(it) }
                }
                "circle" -> num("r").takeIf { it > 0 }?.let { shapes += GlyphShape.C(num("cx"), num("cy"), it) }
                "rect" -> {
                    val w = num("width")
                    val h = num("height")
                    if (w > 0 && h > 0) shapes += GlyphShape.R(num("x"), num("y"), w, h, num("rx"))
                }
            }
            if (shapes.size > before) {
                shapes.last().dash = dash(attrs["stroke-dasharray"])
                shapes.last().rotate = rotation(attrs["transform"])
            }
        }
        return if (shapes.isEmpty()) null else Glyph(shapes, width, cap, join, rootDash)
    }

    private val ROTATE = Regex("""^\s*rotate\(\s*(-?[0-9.]+)(?:[ ,]+(-?[0-9.]+)[ ,]+(-?[0-9.]+))?\s*\)\s*$""")

    private fun rotation(value: String?): Triple<Float, Float, Float>? {
        val m = value?.let { ROTATE.matchEntire(it) } ?: return null
        val a = m.groupValues[1].toFloatOrNull() ?: return null
        return Triple(a, m.groupValues[2].toFloatOrNull() ?: 0f, m.groupValues[3].toFloatOrNull() ?: 0f)
    }

    private fun dash(value: String?): FloatArray? {
        val parts = value?.split(Regex("[ ,]+"))?.mapNotNull { it.toFloatOrNull()?.takeIf { f -> f > 0 && f < 64 } }.orEmpty()
        if (parts.isEmpty()) return null
        return (if (parts.size % 2 == 1) parts + parts else parts).take(8).toFloatArray()
    }
}
