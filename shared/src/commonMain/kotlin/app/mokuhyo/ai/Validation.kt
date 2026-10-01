package app.mokuhyo.ai

/**
 * Generic checks applied to structured AI output before it reaches the learner. Small models drift: they rewrite a
 * whole sentence when asked for a minimal correction, or answer at the wrong length. Language checks live in
 * [app.mokuhyo.lang.ScriptCheck]. Anything that fails is retried once and then falls back.
 */
object Validation {
    fun length(field: String, text: String, min: Int = 1, max: Int): String? {
        val n = text.cpCount()
        return when {
            n < min -> "$field is too short ($n < $min)"
            n > max -> "$field is too long ($n > $max)"
            else -> null
        }
    }

    /** Levenshtein distance over code points. */
    fun levenshtein(a: String, b: String): Int {
        val x = a.cpArray()
        val y = b.cpArray()
        if (x.isEmpty()) return y.size
        if (y.isEmpty()) return x.size
        var prev = IntArray(y.size + 1) { it }
        var cur = IntArray(y.size + 1)
        for (i in 1..x.size) {
            cur[0] = i
            for (j in 1..y.size) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[y.size]
    }

    /**
     * A correction must stay close to what the learner wrote: at most max(4, 40% of the original's length)
     * code-point edits. A model that rewrites the whole sentence is not correcting it.
     */
    fun minimalEdit(original: String, corrected: String, ratio: Double = 0.4, floor: Int = 4): String? {
        val limit = maxOf(floor, (original.cpCount() * ratio).toInt())
        val d = levenshtein(original.trim(), corrected.trim())
        return if (d > limit) "correction changes too much ($d edits > $limit)" else null
    }

    private fun String.cpArray(): IntArray {
        val out = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
                out += 0x10000 + ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00)
                i += 2
            } else {
                out += c.code
                i++
            }
        }
        return out.toIntArray()
    }

    private fun String.cpCount(): Int = cpArray().size
}

/** What validators may consult beyond the output itself (language packs could add a word-acceptance check here). */
class ValidationContext(
    val isKnownWord: (language: String, text: String) -> Boolean = { _, _ -> true },
)
