package app.tsumugi.kanji

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** A node's place in a unit square (0…1 on both axes); the platform scales it to the view. */
data class NodePosition(val id: String, val x: Double, val y: Double)

/**
 * A small deterministic force-directed layout (Fruchterman–Reingold) for the kanji explorer's one-hop graphs (≤ ~50
 * nodes). No randomness: nodes start on a circle in input order, the [pinned] node stays at the center, and the
 * temperature cools linearly over a fixed number of iterations, so the same graph always gets the same picture and a
 * re-centered view doesn't jump around between frames. Coordinates are rounded to 1e-6 to keep them stable.
 */
object ForceLayout {
    const val DEFAULT_ITERATIONS = 150
    private const val MARGIN = 0.06
    private const val MIN_DISTANCE = 1e-4

    fun layout(
        ids: List<String>,
        edges: List<Pair<String, String>>,
        pinned: String? = null,
        iterations: Int = DEFAULT_ITERATIONS,
    ): List<NodePosition> {
        val nodes = ids.distinct()
        val n = nodes.size
        if (n == 0) return emptyList()
        val index = nodes.withIndex().associate { it.value to it.index }
        val pin = pinned?.let { index[it] }
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        // Start on a circle (the pinned node at the center), in input order.
        val ring = nodes.indices.filter { it != pin }
        ring.forEachIndexed { k, i ->
            val angle = 2 * PI * k / ring.size.coerceAtLeast(1) - PI / 2
            x[i] = 0.5 + 0.35 * cos(angle)
            y[i] = 0.5 + 0.35 * sin(angle)
        }
        pin?.let { x[it] = 0.5; y[it] = 0.5 }
        if (n == 1) return listOf(NodePosition(nodes[0], 0.5, 0.5))

        val links = edges.mapNotNull { (a, b) -> index[a]?.let { ia -> index[b]?.let { ib -> if (ia != ib) ia to ib else null } } }.distinct()
        val k = sqrt(1.0 / n) * 0.9 // ideal edge length in the unit square
        val dx = DoubleArray(n)
        val dy = DoubleArray(n)
        val start = 0.1
        repeat(iterations.coerceAtLeast(0)) { step ->
            dx.fill(0.0)
            dy.fill(0.0)
            for (i in 0 until n) for (j in i + 1 until n) {
                var ddx = x[i] - x[j]
                var ddy = y[i] - y[j]
                var d = sqrt(ddx * ddx + ddy * ddy)
                if (d < MIN_DISTANCE) { // coincident: separate along a fixed direction derived from the indices
                    ddx = MIN_DISTANCE * ((i - j) % 3 + 1)
                    ddy = MIN_DISTANCE * ((j + i) % 2 * 2 - 1)
                    d = sqrt(ddx * ddx + ddy * ddy)
                }
                val force = k * k / d
                dx[i] += ddx / d * force; dy[i] += ddy / d * force
                dx[j] -= ddx / d * force; dy[j] -= ddy / d * force
            }
            for ((a, b) in links) {
                val ddx = x[a] - x[b]
                val ddy = y[a] - y[b]
                val d = max(sqrt(ddx * ddx + ddy * ddy), MIN_DISTANCE)
                val force = d * d / k
                dx[a] -= ddx / d * force; dy[a] -= ddy / d * force
                dx[b] += ddx / d * force; dy[b] += ddy / d * force
            }
            val temperature = start * (1.0 - step.toDouble() / iterations)
            for (i in 0 until n) {
                if (i == pin) continue
                val len = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), MIN_DISTANCE)
                val move = min(len, temperature)
                x[i] = (x[i] + dx[i] / len * move).coerceIn(MARGIN, 1 - MARGIN)
                y[i] = (y[i] + dy[i] / len * move).coerceIn(MARGIN, 1 - MARGIN)
            }
        }
        return nodes.indices.map { NodePosition(nodes[it], stable(x[it]), stable(y[it])) }
    }

    private fun stable(v: Double): Double = round(v * 1e6) / 1e6
}
