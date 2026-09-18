package app.tsumugi.jp.strokes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Size of the KanjiVG coordinate space; apps draw on a canvas scaled to it. */
const val KANJIVG_SIZE = 109f

/** Pure geometry for comparing hand-drawn strokes with KanjiVG strokes. */
internal object StrokeGeometry {

    fun length(points: List<Point>): Float {
        var total = 0f
        for (i in 1 until points.size) total += dist(points[i - 1], points[i])
        return total
    }

    fun dist(a: Point, b: Point): Float = hypot(a.x - b.x, a.y - b.y)

    /** [n] points equally spaced along the polyline (a single point is repeated). */
    fun resample(points: List<Point>, n: Int): List<Point> {
        require(n >= 2)
        if (points.isEmpty()) return emptyList()
        val total = length(points)
        if (points.size == 1 || total == 0f) return List(n) { points.first() }
        val step = total / (n - 1)
        val out = ArrayList<Point>(n)
        out += points.first()
        var carried = 0f
        var i = 1
        var prev = points.first()
        while (i < points.size && out.size < n - 1) {
            val next = points[i]
            val seg = dist(prev, next)
            if (carried + seg >= step && seg > 0f) {
                val t = (step - carried) / seg
                val p = Point(prev.x + (next.x - prev.x) * t, prev.y + (next.y - prev.y) * t)
                out += p
                prev = p
                carried = 0f
            } else {
                carried += seg
                prev = next
                i++
            }
        }
        while (out.size < n) out += points.last()
        return out
    }

    /** Uniform scale of every point (e.g. canvas units → unit box). */
    fun scale(points: List<Point>, factor: Float): List<Point> = points.map { Point(it.x * factor, it.y * factor) }

    data class Box(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
        val width get() = maxX - minX
        val height get() = maxY - minY
        val centerX get() = (minX + maxX) / 2
        val centerY get() = (minY + maxY) / 2
    }

    fun box(strokes: List<List<Point>>): Box {
        val all = strokes.flatten()
        return Box(all.minOf { it.x }, all.minOf { it.y }, all.maxOf { it.x }, all.maxOf { it.y })
    }

    /** Maps a whole character into the unit box, preserving aspect ratio and centring it. */
    fun normalizeCharacter(strokes: List<List<Point>>): List<List<Point>> {
        if (strokes.isEmpty() || strokes.all { it.isEmpty() }) return strokes
        val b = box(strokes.filter { it.isNotEmpty() })
        val size = max(max(b.width, b.height), 1e-3f)
        return strokes.map { s -> s.map { Point((it.x - b.centerX) / size + 0.5f, (it.y - b.centerY) / size + 0.5f) } }
    }

    /** Maps [strokes] so their character box matches [target]'s box (aspect preserved, centres aligned). */
    fun fitTo(strokes: List<List<Point>>, target: Box): List<List<Point>> {
        val b = box(strokes)
        val scale = max(target.width, target.height) / max(max(b.width, b.height), 1e-3f)
        return strokes.map { s -> s.map { Point((it.x - b.centerX) * scale + target.centerX, (it.y - b.centerY) * scale + target.centerY) } }
    }

    /**
     * Dynamic time warping cost of the best monotone alignment, divided by the longer sequence's length
     * (inputs are resampled to equal lengths, so this is roughly a mean point distance).
     */
    fun dtw(a: List<Point>, b: List<Point>): Float {
        val n = a.size
        val m = b.size
        val inf = Float.MAX_VALUE
        var prev = FloatArray(m + 1) { inf }
        var cur = FloatArray(m + 1) { inf }
        prev[0] = 0f
        for (i in 1..n) {
            cur[0] = inf
            for (j in 1..m) {
                cur[j] = dist(a[i - 1], b[j - 1]) + minOf3(prev[j - 1], prev[j], cur[j - 1])
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m] / max(n, m)
    }

    /** 8-bin direction histogram weighted by segment length, normalized to sum 1. */
    fun directionHistogram(points: List<Point>): FloatArray {
        val h = FloatArray(BINS)
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            val len = hypot(dx, dy)
            if (len == 0f) continue
            val angle = (atan2(dy, dx) + 2 * PI) % (2 * PI)
            val pos = (angle / (2 * PI) * BINS).toFloat()
            val lo = pos.toInt() % BINS
            val frac = pos - pos.toInt()
            // Soft binning so directions near a bin edge don't flip abruptly.
            h[lo] += len * (1 - frac)
            h[(lo + 1) % BINS] += len * frac
        }
        val sum = h.sum()
        if (sum > 0) for (i in h.indices) h[i] /= sum
        return h
    }

    /** L1 distance of two normalized histograms, in 0..2. */
    fun histogramDistance(a: FloatArray, b: FloatArray): Float {
        var d = 0f
        for (i in a.indices) d += abs(a[i] - b[i])
        return d
    }

    fun centroid(points: List<Point>): Point =
        Point(points.map { it.x }.average().toFloat(), points.map { it.y }.average().toFloat())

    fun translate(points: List<Point>, dx: Float, dy: Float): List<Point> = points.map { Point(it.x + dx, it.y + dy) }

    /** Mean distance between two equally sampled polylines (no warping). */
    fun meanDistance(a: FloatArray, b: FloatArray, reversedB: Boolean = false): Float {
        val n = a.size / 2
        var sum = 0f
        for (i in 0 until n) {
            val j = if (reversedB) n - 1 - i else i
            val dx = a[2 * i] - b[2 * j]
            val dy = a[2 * i + 1] - b[2 * j + 1]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / n
    }

    fun flatten(points: List<Point>): FloatArray {
        val out = FloatArray(points.size * 2)
        points.forEachIndexed { i, p -> out[2 * i] = p.x; out[2 * i + 1] = p.y }
        return out
    }

    fun minOf3(a: Float, b: Float, c: Float) = min(a, min(b, c))

    const val BINS = 8
}
