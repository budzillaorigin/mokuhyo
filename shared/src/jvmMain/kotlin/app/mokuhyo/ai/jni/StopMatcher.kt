package app.mokuhyo.ai.jni

/**
 * Stop strings over a token stream. Text is released for streaming only once it can no longer be the start of a
 * stop string, so a stop string never leaks into `onToken` even when it arrives split across tokens; the final
 * [text] excludes the stop string and everything after it.
 */
internal class StopMatcher(stops: List<String>) {
    private val stops = stops.filter { it.isNotEmpty() }.distinct()
    private val longest = this.stops.maxOfOrNull { it.length } ?: 0
    private val buffer = StringBuilder()
    private var released = 0

    /** True once a stop string was seen; [append] then ignores further input. */
    var stopped: Boolean = false
        private set

    /** The text so far (final once [stopped] or after [finish]), without any stop string. */
    val text: String get() = buffer.toString()

    /** Adds a decoded piece; returns the text now safe to stream (possibly empty). */
    fun append(piece: String): String {
        if (stopped) return ""
        val searchFrom = (buffer.length - longest + 1).coerceAtLeast(0)
        buffer.append(piece)
        if (stops.isNotEmpty()) {
            val cut = stops.mapNotNull { s -> buffer.indexOf(s, searchFrom).takeIf { it >= 0 } }.minOrNull()
            if (cut != null) {
                buffer.setLength(cut)
                stopped = true
                return release(cut)
            }
        }
        return release(buffer.length - heldBack())
    }

    /** Releases whatever is still held back (generation ended without a stop string). */
    fun finish(): String = release(buffer.length)

    /** Length of the longest buffer suffix that is a proper prefix of some stop string. */
    private fun heldBack(): Int {
        for (k in minOf(longest - 1, buffer.length) downTo 1) {
            val start = buffer.length - k
            if (stops.any { it.length > k && buffer.regionMatches(start, it, 0, k) }) return k
        }
        return 0
    }

    private fun release(upTo: Int): String {
        if (upTo <= released) return ""
        val out = buffer.substring(released, upTo)
        released = upTo
        return out
    }

    private fun StringBuilder.regionMatches(start: Int, other: String, otherStart: Int, length: Int): Boolean {
        for (i in 0 until length) if (this[start + i] != other[otherStart + i]) return false
        return true
    }
}
