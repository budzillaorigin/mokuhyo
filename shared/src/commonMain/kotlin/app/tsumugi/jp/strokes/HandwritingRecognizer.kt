package app.tsumugi.jp.strokes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Where recognition templates come from: kanji → its strokes (KanjiVG polylines, 109×109 space). */
fun interface StrokeTemplateSource {
    suspend fun candidates(strokeCounts: IntRange): Map<String, List<List<Point>>>
}

/**
 * Adapts any loader (e.g. the dictionary pack's `stroke` table) into a [StrokeTemplateSource]. [load] returns
 * every kanji's SVG path data in stroke order; they're flattened here once.
 */
class SvgTemplateSource(private val load: suspend () -> Map<String, List<String>>) : StrokeTemplateSource {
    private var cache: Map<String, List<List<Point>>>? = null

    override suspend fun candidates(strokeCounts: IntRange): Map<String, List<List<Point>>> {
        val all = cache ?: load().mapValues { (_, paths) -> paths.map { SvgPath.flatten(it, step = 3f) } }.also { cache = it }
        return all.filterValues { it.size in strokeCounts }
    }
}

/** [score] is 0..1, higher is a better match. */
data class Candidate(val kanji: String, val score: Double)

/**
 * Handwriting search (BRIEF §5.3): draw a kanji, get likely candidates.
 *
 * This replaces the trained Core ML / TFLite classifier the brief sketches with template matching against
 * KanjiVG, which needs no model training, behaves identically on both platforms and covers every KanjiVG kanji.
 * Both drawing and templates are normalized to a unit box; each stroke becomes 8 evenly spaced points.
 * A candidate's cost is the smaller of:
 * - an order-aware alignment (drawn stroke i against template stroke i, direction-tolerant), and
 * - an order-tolerant greedy assignment of drawn strokes to template strokes (small penalty),
 * plus a penalty per stroke of difference in count. Only kanji within ±[STROKE_WINDOW] strokes are considered.
 */
class HandwritingRecognizer(private val source: StrokeTemplateSource) {
    private val lock = Mutex()
    private var templates: List<Template>? = null

    private class Template(val kanji: String, val strokes: List<FloatArray>)

    suspend fun recognize(strokes: List<List<Point>>, limit: Int = 10): List<Candidate> {
        val drawn = strokes.filter { it.isNotEmpty() }
        if (drawn.isEmpty()) return emptyList()
        val all = templates()
        return withContext(Dispatchers.Default) {
            val features = features(drawn)
            val n = features.size
            all.asSequence()
                .filter { abs(it.strokes.size - n) <= STROKE_WINDOW }
                .map { t -> t.kanji to cost(features, t.strokes) }
                .sortedBy { it.second }
                .take(limit)
                .map { (k, c) -> Candidate(k, (1.0 - c * 4).coerceIn(0.0, 1.0)) }
                .toList()
        }
    }

    /** Loads and prepares every template once (the first query pays for it). */
    suspend fun warmUp() {
        templates()
    }

    private suspend fun templates(): List<Template> = lock.withLock {
        templates ?: withContext(Dispatchers.IO) {
            source.candidates(1..MAX_STROKES).map { (k, s) -> Template(k, features(s)) }
        }.also { templates = it }
    }

    private fun cost(drawn: List<FloatArray>, template: List<FloatArray>): Float {
        val n = drawn.size
        val m = template.size
        val countPenalty = abs(n - m) * COUNT_PENALTY
        val size = max(n, m)

        // Order-aware: stroke i with stroke i (either direction; reversal costs a little).
        var ordered = 0f
        for (i in 0 until min(n, m)) ordered += strokeCost(drawn[i], template[i])
        ordered = (ordered + abs(n - m) * UNMATCHED) / size
        if (ordered < 0.02f) return ordered + countPenalty

        // Order-tolerant: greedily pair the cheapest drawn/template strokes.
        val pairs = ArrayList<Triple<Float, Int, Int>>(n * m)
        for (i in 0 until n) for (j in 0 until m) pairs += Triple(strokeCost(drawn[i], template[j]), i, j)
        pairs.sortBy { it.first }
        val usedD = BooleanArray(n)
        val usedT = BooleanArray(m)
        var greedy = 0f
        var matched = 0
        for ((c, i, j) in pairs) {
            if (usedD[i] || usedT[j]) continue
            usedD[i] = true
            usedT[j] = true
            greedy += c
            if (++matched == min(n, m)) break
        }
        greedy = (greedy + abs(n - m) * UNMATCHED) / size + ORDER_PENALTY
        return min(ordered, greedy) + countPenalty
    }

    private fun strokeCost(a: FloatArray, b: FloatArray): Float =
        min(StrokeGeometry.meanDistance(a, b), StrokeGeometry.meanDistance(a, b, reversedB = true) + REVERSED_PENALTY)

    private fun features(strokes: List<List<Point>>): List<FloatArray> =
        StrokeGeometry.normalizeCharacter(strokes).map { StrokeGeometry.flatten(StrokeGeometry.resample(it, POINTS)) }

    companion object {
        const val STROKE_WINDOW = 2
        const val MAX_STROKES = 40
        private const val POINTS = 8
        private const val COUNT_PENALTY = 0.03f
        private const val UNMATCHED = 0.35f
        private const val ORDER_PENALTY = 0.015f
        private const val REVERSED_PENALTY = 0.04f
    }
}
