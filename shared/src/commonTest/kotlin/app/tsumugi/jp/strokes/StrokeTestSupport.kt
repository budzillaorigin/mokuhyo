package app.tsumugi.jp.strokes

import kotlin.random.Random

/** Deterministic "handwriting" distortions of template strokes. */
internal object Distort {
    /** Scales about the canvas centre, translates, and adds per-point noise of up to [noise] units. */
    fun character(
        strokes: List<List<Point>>,
        random: Random,
        scale: Float = 1f,
        dx: Float = 0f,
        dy: Float = 0f,
        noise: Float = 0f,
    ): List<List<Point>> {
        val c = KANJIVG_SIZE / 2
        return strokes.map { s ->
            // Smooth noise: one random offset per stroke end, interpolated, plus a little per-point wobble.
            val ox0 = random.nextFloat() * 2 - 1
            val oy0 = random.nextFloat() * 2 - 1
            val ox1 = random.nextFloat() * 2 - 1
            val oy1 = random.nextFloat() * 2 - 1
            s.mapIndexed { i, p ->
                val t = if (s.size > 1) i.toFloat() / (s.size - 1) else 0f
                val nx = (ox0 * (1 - t) + ox1 * t) * noise + (random.nextFloat() - 0.5f) * noise * 0.3f
                val ny = (oy0 * (1 - t) + oy1 * t) * noise + (random.nextFloat() - 0.5f) * noise * 0.3f
                Point((p.x - c) * scale + c + dx + nx, (p.y - c) * scale + c + dy + ny)
            }
        }
    }
}
