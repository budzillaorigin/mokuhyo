package app.tsumugi.ai

import app.tsumugi.jp.Kana

/**
 * Checks applied to every structured AI output before it reaches the learner (BRIEF §7.4). Small models drift:
 * they leak English or Chinese-style romanization into Japanese fields, rewrite a whole sentence when asked for a
 * minimal correction, or invent words. Anything that fails is retried once and then falls back.
 */
object Validation {
    /**
     * A problem description if [text], which should be Japanese, contains another script: Hangul, Cyrillic,
     * Greek, Thai, Arabic, Devanagari, or a run of 3+ lowercase Latin letters (English words, romaji). Uppercase
     * acronyms (CD, NHK), digits and full-width forms are allowed. Returns null when the text is clean.
     */
    fun scriptLeak(text: String): String? {
        var latinRun = 0
        for (c in text) {
            val code = c.code
            val foreign = when (code) {
                in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> "Korean"
                in 0x0400..0x04FF -> "Cyrillic"
                in 0x0370..0x03FF -> "Greek"
                in 0x0E00..0x0E7F -> "Thai"
                in 0x0600..0x06FF -> "Arabic"
                in 0x0900..0x097F -> "Devanagari"
                else -> null
            }
            if (foreign != null) return "contains $foreign text"
            latinRun = if (c in 'a'..'z') latinRun + 1 else 0
            if (latinRun >= 3) return "contains Latin-script words"
        }
        return null
    }

    /** A problem description unless [text] actually contains Japanese (kana or kanji). */
    fun requireJapanese(field: String, text: String): String? = when {
        text.isBlank() -> "$field is empty"
        !text.any { Kana.isKana(it) } && !Kana.containsKanji(text) -> "$field is not Japanese"
        else -> scriptLeak(text)?.let { "$field $it" }
    }

    /** A problem description if an English field is mostly Japanese (the model answered in the wrong language). */
    fun requireEnglish(field: String, text: String): String? {
        if (text.isBlank()) return "$field is empty"
        val japanese = text.count { Kana.isKana(it) || Kana.isKanji(it.code) }
        val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        return if (japanese > latin) "$field is not English" else null
    }

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

/**
 * What validators may consult beyond the output itself. [isKnownJapanese] is the dictionary/deinflector acceptance
 * check the app injects (tokenize, deinflect, look up): it returns false when a Japanese sentence contains a word
 * that no dictionary entry or conjugation explains, which is how invented words are caught. Default: accept all.
 */
class ValidationContext(
    val isKnownJapanese: (String) -> Boolean = { true },
)
