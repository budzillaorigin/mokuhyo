package app.tsumugi.jp.strokes

import app.tsumugi.jp.strokes.StrokeGeometry.centroid
import app.tsumugi.jp.strokes.StrokeGeometry.directionHistogram
import app.tsumugi.jp.strokes.StrokeGeometry.dist
import app.tsumugi.jp.strokes.StrokeGeometry.dtw
import app.tsumugi.jp.strokes.StrokeGeometry.histogramDistance
import app.tsumugi.jp.strokes.StrokeGeometry.length
import app.tsumugi.jp.strokes.StrokeGeometry.resample
import app.tsumugi.jp.strokes.StrokeGeometry.scale
import app.tsumugi.jp.strokes.StrokeGeometry.translate
import kotlin.math.max

enum class StrokeProblem { WRONG_DIRECTION, WRONG_ORDER, WRONG_SHAPE, WRONG_POSITION, TOO_SHORT }

/** How forgiving grading is: the maximum combined cost (in unit-box distances) that still passes. */
enum class Strictness(internal val tolerance: Float) { LENIENT(0.16f), NORMAL(0.11f), STRICT(0.075f) }

/**
 * [score] is 0..1 (1 = matches the template exactly). [problem] is set when the stroke was rejected.
 * [index] is the template stroke the drawn stroke was graded against.
 */
data class StrokeResult(val index: Int, val accepted: Boolean, val score: Double, val problem: StrokeProblem?)

/**
 * Compares one drawn stroke with one KanjiVG stroke (BRIEF §5.7): both are resampled to 32 points and
 * scaled into the unit box, then compared by dynamic time warping, start/end proximity and a direction
 * histogram. Everything is in canvas space (the template is drawn where the learner writes).
 */
internal object StrokeGrader {
    const val SAMPLES = 32

    /** Resampled, unit-box copy of a stroke plus its direction histogram. */
    class Prepared(points: List<Point>) {
        val rawLength: Float = length(points)
        val pts: List<Point> = scale(resample(points.ifEmpty { listOf(Point(0f, 0f)) }, SAMPLES), 1f / KANJIVG_SIZE)
        val length: Float = rawLength / KANJIVG_SIZE
        val histogram: FloatArray = directionHistogram(pts)
        val reversed: List<Point> by lazy { pts.reversed() }
        val reversedHistogram: FloatArray by lazy { directionHistogram(reversed) }
    }

    /** Combined cost: warped path distance + endpoint distance + direction mismatch. */
    fun cost(drawn: Prepared, template: Prepared, reverse: Boolean = false): Float {
        val t = if (reverse) template.reversed else template.pts
        val h = if (reverse) template.reversedHistogram else template.histogram
        val endpoints = (dist(drawn.pts.first(), t.first()) + dist(drawn.pts.last(), t.last())) / 2
        return dtw(drawn.pts, t) + 0.5f * endpoints + 0.12f * histogramDistance(drawn.histogram, h)
    }

    /** Shape-only cost (both strokes moved to the same centroid). */
    fun shapeCost(drawn: Prepared, template: Prepared): Float {
        val a = centroid(drawn.pts)
        val b = centroid(template.pts)
        val moved = translate(drawn.pts, b.x - a.x, b.y - a.y)
        return dtw(moved, template.pts) + 0.12f * histogramDistance(drawn.histogram, template.histogram)
    }

    fun score(cost: Float, tolerance: Float): Double = (1.0 - cost / (2.5 * tolerance)).coerceIn(0.0, 1.0)

    /**
     * Grades [drawn] against template stroke [index]. When it fails, the problem is diagnosed: drawn backwards,
     * matches a later stroke (wrong order), right shape in the wrong place, too short, or just wrong.
     */
    fun grade(drawn: Prepared, templates: List<Prepared>, index: Int, strictness: Strictness): StrokeResult {
        val tol = strictness.tolerance
        val expected = templates[index]
        val c = cost(drawn, expected)
        // Short strokes (dots) are hard to hit exactly; allow a little extra for them.
        val allowance = tol * (1f + 0.5f * (1f - (expected.length / 0.25f).coerceIn(0f, 1f)))
        val laterMatch = (index + 1 until templates.size).firstOrNull { cost(drawn, templates[it]) < c * 0.6f && cost(drawn, templates[it]) <= allowance }
        if (c <= allowance && laterMatch == null) return StrokeResult(index, true, score(c, allowance), null)

        val problem = when {
            drawn.length < max(0.02f, 0.35f * expected.length) -> StrokeProblem.TOO_SHORT
            laterMatch != null -> StrokeProblem.WRONG_ORDER
            cost(drawn, expected, reverse = true) <= allowance -> StrokeProblem.WRONG_DIRECTION
            shapeCost(drawn, expected) <= allowance * 0.8f -> StrokeProblem.WRONG_POSITION
            else -> StrokeProblem.WRONG_SHAPE
        }
        return StrokeResult(index, false, score(c, allowance), problem)
    }
}

/**
 * Skritter-style guided writing (BRIEF §5.7): the learner draws one stroke at a time over a faded template;
 * each stroke is graded against the next expected KanjiVG stroke. After [HINT_AFTER_FAILURES] misses on the same
 * stroke the UI should show it ([hint]). Points are in canvas units 0..109 (callers scale their canvas).
 */
class WritingSession(template: List<List<Point>>, private val strictness: Strictness = Strictness.NORMAL) {
    private val strokes = template
    private val prepared = template.map { StrokeGrader.Prepared(it) }
    private val results = ArrayList<StrokeResult>()

    var expectedIndex: Int = 0
        private set
    val done: Boolean get() = expectedIndex >= strokes.size
    var failuresOnCurrent: Int = 0
        private set
    /** Total misses over the whole character (for rating the review afterwards). */
    var totalFailures: Int = 0
        private set
    val accepted: List<StrokeResult> get() = results.toList()

    fun submit(stroke: List<Point>): StrokeResult {
        check(!done) { "all strokes already written" }
        val result = StrokeGrader.grade(StrokeGrader.Prepared(stroke), prepared, expectedIndex, strictness)
        if (result.accepted) {
            results += result
            expectedIndex++
            failuresOnCurrent = 0
        } else {
            failuresOnCurrent++
            totalFailures++
        }
        return result
    }

    /** The expected stroke's polyline (for the hint animation / reveal after repeated misses). */
    fun hint(): List<Point> = strokes[expectedIndex.coerceAtMost(strokes.lastIndex)]

    /** Accept the current stroke after revealing it (the learner traces the shown stroke). */
    fun skip() {
        if (done) return
        results += StrokeResult(expectedIndex, true, 0.0, null)
        expectedIndex++
        failuresOnCurrent = 0
        totalFailures++
    }

    /** Suggested 1–4 rating for the writing card, from misses. */
    val suggestedRating: Int
        get() = when {
            totalFailures == 0 -> 4
            totalFailures == 1 -> 3
            totalFailures <= strokes.size / 3 + 1 -> 2
            else -> 1
        }

    companion object {
        const val HINT_AFTER_FAILURES = 3
    }
}

/** Result of a raw ("no template shown") writing review. */
data class RawResult(
    val countOk: Boolean,
    val orderOk: Boolean,
    /** Per drawn stroke: similarity 0..1 with the template stroke at the same position (0 when there's none). */
    val scores: List<Double>,
    /** 1 = again … 4 = easy; the learner still confirms after seeing the reference. */
    val suggestedRating: Int,
)

/**
 * Raw-squigg reviews (BRIEF §5.7): the learner writes the whole kanji freely. The drawing is fitted onto the
 * template's box, then stroke count, stroke order and per-stroke shape are checked.
 */
object RawWritingChecker {
    fun check(template: List<List<Point>>, strokes: List<List<Point>>, strictness: Strictness = Strictness.NORMAL): RawResult {
        val drawn = strokes.filter { it.isNotEmpty() }
        if (drawn.isEmpty() || template.isEmpty()) return RawResult(false, false, emptyList(), 1)
        val fitted = StrokeGeometry.fitTo(drawn, StrokeGeometry.box(template))
        val t = template.map { StrokeGrader.Prepared(it) }
        val d = fitted.map { StrokeGrader.Prepared(it) }
        val tol = strictness.tolerance * 1.3f // free writing is less precise than tracing
        val scores = d.mapIndexed { i, s -> if (i < t.size) StrokeGrader.score(StrokeGrader.cost(s, t[i]), tol) else 0.0 }
        val best = d.map { s -> t.indices.minBy { StrokeGrader.cost(s, t[it]) } }
        val orderOk = best.zipWithNext().all { (a, b) -> a < b } && best.indices.all { best[it] == it || scores[it] >= 0.5 }
        val countOk = d.size == t.size
        val mean = scores.sum() / max(d.size, t.size)
        val rating = when {
            kotlin.math.abs(d.size - t.size) > 1 || mean < 0.35 -> 1
            !countOk || !orderOk || mean < 0.55 -> 2
            mean < 0.85 -> 3
            else -> 4
        }
        return RawResult(countOk, orderOk, scores, rating)
    }
}
