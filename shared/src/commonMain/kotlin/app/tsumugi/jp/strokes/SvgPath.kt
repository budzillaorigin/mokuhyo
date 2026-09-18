package app.tsumugi.jp.strokes

import kotlin.math.ceil
import kotlin.math.hypot

data class Point(val x: Float, val y: Float)

/**
 * Minimal SVG path-data reader for KanjiVG strokes (M, L, H, V, C, S, Z; absolute and relative),
 * flattened to polylines so both apps can draw and animate strokes without platform SVG support.
 * Coordinates stay in KanjiVG's 109×109 space; views scale them.
 */
object SvgPath {

    /** Flattens [d] into a polyline with points roughly every [step] units along each curve. */
    fun flatten(d: String, step: Float = 1.5f): List<Point> {
        val out = ArrayList<Point>()
        val tokens = tokenize(d)
        var i = 0
        var cmd = ' '
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var prevWasCurve = false

        fun num(): Float = (tokens[i++] as Token.Num).value
        fun hasNum() = i < tokens.size && tokens[i] is Token.Num
        fun lineTo(x: Float, y: Float) {
            out += Point(x, y)
            cx = x; cy = y
        }
        fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            val approx = hypot(x1 - cx, y1 - cy) + hypot(x2 - x1, y2 - y1) + hypot(x - x2, y - y2)
            val n = maxOf(2, ceil(approx / step).toInt())
            for (k in 1..n) {
                val t = k.toFloat() / n
                val mt = 1 - t
                out += Point(
                    mt * mt * mt * cx + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x,
                    mt * mt * mt * cy + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t * t * t * y,
                )
            }
            lastCtrlX = x2; lastCtrlY = y2
            cx = x; cy = y
        }

        while (i < tokens.size) {
            val t = tokens[i]
            if (t is Token.Cmd) {
                cmd = t.c
                i++
            }
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            var curve = false
            when (cmd.uppercaseChar()) {
                'M' -> {
                    val x = num() + ox
                    val y = num() + oy
                    cx = x; cy = y; startX = x; startY = y
                    out += Point(x, y)
                    cmd = if (rel) 'l' else 'L' // subsequent pairs are implicit line-tos
                }
                'L' -> lineTo(num() + ox, num() + oy)
                'H' -> lineTo(num() + ox, cy)
                'V' -> lineTo(cx, num() + oy)
                'C' -> {
                    val x1 = num() + ox; val y1 = num() + oy
                    val x2 = num() + ox; val y2 = num() + oy
                    val x = num() + ox; val y = num() + oy
                    curveTo(x1, y1, x2, y2, x, y)
                    curve = true
                }
                'S' -> {
                    val x1 = if (prevWasCurve) 2 * cx - lastCtrlX else cx
                    val y1 = if (prevWasCurve) 2 * cy - lastCtrlY else cy
                    val x2 = num() + ox; val y2 = num() + oy
                    val x = num() + ox; val y = num() + oy
                    curveTo(x1, y1, x2, y2, x, y)
                    curve = true
                }
                'Z' -> lineTo(startX, startY)
                else -> error("unsupported SVG path command '$cmd' in: $d")
            }
            prevWasCurve = curve
            // Z takes no arguments, so a number right after it would never be consumed.
            require(cmd.uppercaseChar() != 'Z' || !hasNum()) { "numbers after Z in: $d" }
        }
        return out
    }

    private sealed interface Token {
        data class Cmd(val c: Char) : Token
        data class Num(val value: Float) : Token
    }

    private fun tokenize(d: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isLetter() && c != 'e' && c != 'E' -> { out += Token.Cmd(c); i++ }
                c == '-' || c == '+' || c == '.' || c.isDigit() -> {
                    val start = i
                    i++
                    var seenDot = c == '.'
                    while (i < d.length) {
                        val ch = d[i]
                        if (ch.isDigit()) i++
                        else if (ch == '.' && !seenDot) { seenDot = true; i++ }
                        else if ((ch == 'e' || ch == 'E')) {
                            i++
                            if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
                        } else break
                    }
                    out += Token.Num(d.substring(start, i).toFloat())
                }
                else -> i++ // separators: whitespace and commas
            }
        }
        return out
    }
}
