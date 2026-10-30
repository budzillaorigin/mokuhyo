package app.mokuhyo.speech

/**
 * Silence at the edges of a clip (QC 2026-10-30 V2, V12): engines pad speech with their own leading and trailing
 * silence, which the learner hears as part of any gap. A sample is audible when its magnitude reaches [threshold]
 * (default about −40 dBFS); everything before the first and after the last audible sample is edge silence. Quiet
 * speech and breaths inside the clip are never counted or trimmed.
 */
object EdgeSilence {
    const val THRESHOLD = 328

    data class Edges(val leadingMs: Long, val trailingMs: Long, val durationMs: Long) {
        /** No audible sample at all. */
        val silent: Boolean get() = leadingMs >= durationMs
    }

    fun of(pcm: ShortArray, rate: Int, threshold: Int = THRESHOLD): Edges {
        val ms = { n: Int -> n * 1000L / rate }
        val first = pcm.indexOfFirst { kotlin.math.abs(it.toInt()) >= threshold }
        if (first < 0) return Edges(ms(pcm.size), ms(pcm.size), ms(pcm.size))
        val last = pcm.indexOfLast { kotlin.math.abs(it.toInt()) >= threshold }
        return Edges(ms(first), ms(pcm.size - 1 - last), ms(pcm.size))
    }
}
