// Training procedure ported from py-fsrs's Optimizer (MIT), © Open Spaced Repetition.
package app.mokuhyo.srs

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Instant

data class ReviewLogEntry(val cardId: String, val rating: Rating, val reviewedAt: Instant)

data class OptimizerResult(
    val weights: List<Double>,
    /** Mean binary cross-entropy of predicted recall over the log with the initial / fitted weights. */
    val lossBefore: Double,
    val lossAfter: Double,
    /** Number of reviews that contribute to the loss (not same-day, not a card's first review). */
    val reviewCount: Int,
)

/**
 * Fits the 21 FSRS weights to a user's review log, like py-fsrs's Optimizer: binary cross-entropy between
 * predicted retrievability and actual recall (rating > AGAIN) for every review that isn't a card's first or
 * same-day, minimized with Adam (lr 0.04, cosine annealing, mini-batches of 512, 5 epochs), weights clamped
 * to their bounds after every step. Gradients are exact, via forward-mode dual numbers through each card's
 * whole review sequence, so no ML library is needed.
 */
object FsrsOptimizer {
    /** Below this many reviews the defaults are better than anything fitted; callers should not optimize. */
    const val MIN_REVIEWS = 1000

    private const val MINI_BATCH = 512
    private const val LEARNING_RATE = 4e-2
    private const val MAX_SEQ_LEN = 64

    fun optimize(
        log: List<ReviewLogEntry>,
        initial: List<Double> = FsrsParameters.DEFAULT_WEIGHTS,
        iterations: Int = 5,
        seed: Int = 42,
    ): OptimizerResult {
        val sequences = sequences(log)
        val reviewCount = sequences.sumOf { seq -> seq.indices.count { i -> i > 0 && seq[i].daysSinceLast > 0 } }
        val lossBefore = sequenceLoss(sequences, initial)
        if (reviewCount == 0) return OptimizerResult(initial, lossBefore, lossBefore, 0)

        val w = initial.toDoubleArray()
        val m = DoubleArray(N)
        val v = DoubleArray(N)
        var t = 0
        val totalSteps = ceil(reviewCount.toDouble() / MINI_BATCH).toInt() * iterations
        var best = initial
        var bestLoss = lossBefore
        val rng = Random(seed)
        val order = sequences.indices.toMutableList()

        repeat(iterations) {
            order.shuffle(rng)
            val grad = DoubleArray(N)
            var inBatch = 0
            fun step() {
                t++
                val lr = LEARNING_RATE * (1 + cos(PI * (t - 1) / totalSteps)) / 2 // cosine annealing
                for (k in 0 until N) {
                    m[k] = B1 * m[k] + (1 - B1) * grad[k]
                    v[k] = B2 * v[k] + (1 - B2) * grad[k] * grad[k]
                    val mHat = m[k] / (1 - B1.pow(t))
                    val vHat = v[k] / (1 - B2.pow(t))
                    w[k] = (w[k] - lr * mHat / (sqrt(vHat) + EPS))
                        .coerceIn(FsrsParameters.LOWER_BOUNDS[k], FsrsParameters.UPPER_BOUNDS[k])
                    grad[k] = 0.0
                }
                inBatch = 0
            }
            for (idx in order) {
                val params = Array(N) { Dual.variable(w[it], it) }
                forEachLoss(sequences[idx], params) { loss ->
                    for (k in 0 until N) grad[k] += loss.g[k]
                    if (++inBatch == MINI_BATCH) step()
                }
            }
            if (inBatch > 0) step()
            val epochLoss = sequenceLoss(sequences, w.toList())
            if (epochLoss < bestLoss) {
                bestLoss = epochLoss
                best = w.toList()
            }
        }
        return OptimizerResult(best, lossBefore, bestLoss, reviewCount)
    }

    /** Mean loss of [weights] over [log] (same objective the optimizer minimizes). */
    fun loss(log: List<ReviewLogEntry>, weights: List<Double>): Double = sequenceLoss(sequences(log), weights)

    /** Exact gradient of [loss] with respect to each weight (exposed for tests). */
    internal fun gradient(log: List<ReviewLogEntry>, weights: List<Double>): List<Double> {
        val params = Array(N) { Dual.variable(weights[it], it) }
        val grad = DoubleArray(N)
        var count = 0
        for (seq in sequences(log)) forEachLoss(seq, params) { l ->
            for (k in 0 until N) grad[k] += l.g[k]
            count++
        }
        return grad.map { if (count == 0) 0.0 else it / count }
    }

    // --- Internals -------------------------------------------------------------------------------------

    private const val N = FsrsParameters.WEIGHT_COUNT
    private const val B1 = 0.9
    private const val B2 = 0.999
    private const val EPS = 1e-8

    private class Step(val rating: Rating, val daysSinceLast: Long, val recalled: Boolean)

    private fun sequences(log: List<ReviewLogEntry>): List<List<Step>> =
        log.groupBy { it.cardId }.values.map { reviews ->
            val sorted = reviews.sortedBy { it.reviewedAt }.take(MAX_SEQ_LEN)
            sorted.mapIndexed { i, r ->
                val days = if (i == 0) 0 else wholeDaysBetween(sorted[i - 1].reviewedAt, r.reviewedAt)
                Step(r.rating, days, r.rating != Rating.AGAIN)
            }
        }

    private fun sequenceLoss(sequences: List<List<Step>>, weights: List<Double>): Double {
        val params = Array(N) { Dual.constant(weights[it]) }
        var total = 0.0
        var count = 0
        for (seq in sequences) forEachLoss(seq, params) { total += it.v; count++ }
        return if (count == 0) 0.0 else total / count
    }

    /**
     * Runs one card's history through the FSRS memory model (same formulas as [Fsrs], over dual numbers) and
     * reports the BCE loss of each scored review. Memory-state updates don't depend on the card's learning
     * state, only on whether the review was same-day, so the steps/intervals machinery isn't needed here.
     */
    private inline fun forEachLoss(seq: List<Step>, w: Array<Dual>, onLoss: (Dual) -> Unit) {
        val decay = -w[20]
        val factor = (Dual.constant(ln(0.9)) / decay).exp() - 1.0
        var s: Dual? = null
        var d: Dual? = null
        for (step in seq) {
            val r = step.rating.value
            if (s == null || d == null) {
                s = w[r - 1].atLeast(Fsrs.STABILITY_MIN)
                d = clampD(w[4] - (w[5] * (r - 1).toDouble()).exp() + 1.0)
                continue
            }
            val sameDay = step.daysSinceLast < 1
            val nextD = nextDifficulty(w, d, r)
            if (sameDay) {
                var inc = (w[17] * (w[18] + (r - 3).toDouble())).exp() * (s.ln() * -w[19]).exp()
                if (r > 1) inc = inc.atLeast(1.0)
                s = (s * inc).atLeast(Fsrs.STABILITY_MIN)
            } else {
                val t = step.daysSinceLast.toDouble()
                val retr = ((factor * t / s + 1.0).ln() * decay).exp()
                onLoss(bce(retr, step.recalled))
                s = if (r == 1) {
                    val longTerm = w[11] * (d.ln() * -w[12]).exp() * (((s + 1.0).ln() * w[13]).exp() - 1.0) *
                        ((1.0 - retr) * w[14]).exp()
                    val shortTerm = s / (w[17] * w[18]).exp()
                    if (longTerm.v <= shortTerm.v) longTerm else shortTerm
                } else {
                    var growth = w[8].exp() * (11.0 - d) * (s.ln() * -w[9]).exp() * (((1.0 - retr) * w[10]).exp() - 1.0)
                    if (r == 2) growth *= w[15]
                    if (r == 4) growth *= w[16]
                    s * (growth + 1.0)
                }.atLeast(Fsrs.STABILITY_MIN)
            }
            d = nextD
        }
    }

    private fun nextDifficulty(w: Array<Dual>, d: Dual, r: Int): Dual {
        val delta = w[6] * -(r - 3).toDouble()
        val damped = d + (10.0 - d) * delta / 9.0
        val initEasy = w[4] - (w[5] * 3.0).exp() + 1.0
        return clampD(w[7] * initEasy + (1.0 - w[7]) * damped)
    }

    private fun clampD(d: Dual): Dual = d.atLeast(Fsrs.MIN_DIFFICULTY).atMost(Fsrs.MAX_DIFFICULTY)

    private fun bce(p: Dual, recalled: Boolean): Dual {
        val q = p.atLeast(1e-7).atMost(1 - 1e-7)
        return if (recalled) -q.ln() else -(1.0 - q).ln()
    }
}

/** Forward-mode dual number: value plus gradient with respect to the 21 FSRS weights. */
internal class Dual(val v: Double, val g: DoubleArray) {
    operator fun plus(o: Dual) = Dual(v + o.v, DoubleArray(g.size) { g[it] + o.g[it] })
    operator fun plus(c: Double) = Dual(v + c, g)
    operator fun minus(o: Dual) = Dual(v - o.v, DoubleArray(g.size) { g[it] - o.g[it] })
    operator fun minus(c: Double) = Dual(v - c, g)
    operator fun unaryMinus() = Dual(-v, DoubleArray(g.size) { -g[it] })
    operator fun times(o: Dual) = Dual(v * o.v, DoubleArray(g.size) { g[it] * o.v + o.g[it] * v })
    operator fun times(c: Double) = Dual(v * c, DoubleArray(g.size) { g[it] * c })
    operator fun div(o: Dual) = Dual(v / o.v, DoubleArray(g.size) { (g[it] * o.v - o.g[it] * v) / (o.v * o.v) })
    operator fun div(c: Double) = Dual(v / c, DoubleArray(g.size) { g[it] / c })
    fun exp(): Dual { val e = exp(v); return Dual(e, DoubleArray(g.size) { g[it] * e }) }
    fun ln(): Dual = Dual(ln(v), DoubleArray(g.size) { g[it] / v })

    /** max(this, c) / min(this, c): the gradient follows the chosen branch, like torch.clamp. */
    fun atLeast(c: Double) = if (v >= c) this else constant(c, g.size)
    fun atMost(c: Double) = if (v <= c) this else constant(c, g.size)

    companion object {
        fun constant(v: Double, n: Int = FsrsParameters.WEIGHT_COUNT) = Dual(v, DoubleArray(n))
        fun variable(v: Double, index: Int, n: Int = FsrsParameters.WEIGHT_COUNT) =
            Dual(v, DoubleArray(n).also { it[index] = 1.0 })
    }
}

internal operator fun Double.minus(d: Dual) = Dual(this - d.v, DoubleArray(d.g.size) { -d.g[it] })
internal operator fun Double.times(d: Dual) = d * this
