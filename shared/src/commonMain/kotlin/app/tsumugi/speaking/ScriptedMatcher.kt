package app.tsumugi.speaking

import app.tsumugi.jp.Kana
import app.tsumugi.platform.normalizeNfc

/**
 * Loose matching of a learner's reply against a scripted turn's acceptable answers (DECISIONS D-223), used when no
 * model is set up. Replies are NFC-normalized, katakana-folded to hiragana, and stripped of punctuation and spaces,
 * then compared by character-bigram Dice similarity. Deliberately forgiving: the scripted mode is a practice
 * scaffold, not a grader, so the threshold only catches replies that are clearly off-topic or empty.
 */
object ScriptedMatcher {
    /** Dice similarity at or above this counts as a match (checked on the drafted scenarios' alternative answers). */
    const val THRESHOLD = 0.3

    fun matches(reply: String, acceptable: List<String>): Boolean {
        val r = normalize(reply)
        if (r.isEmpty()) return false
        return acceptable.any { answer ->
            val a = normalize(answer)
            a.isNotEmpty() && (r == a || r.contains(a) || a.contains(r) && r.length >= 2 || similarity(r, a) >= THRESHOLD)
        }
    }

    fun normalize(text: String): String = Kana.toHiragana(normalizeNfc(text)).filter { it.isLetterOrDigit() }.lowercase()

    /** Dice coefficient over character bigrams (single characters when a string is one character long). */
    fun similarity(a: String, b: String): Double {
        val x = grams(a)
        val y = grams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val counts = HashMap<String, Int>()
        for (g in y) counts[g] = (counts[g] ?: 0) + 1
        var shared = 0
        for (g in x) {
            val n = counts[g] ?: 0
            if (n > 0) {
                shared++
                counts[g] = n - 1
            }
        }
        return 2.0 * shared / (x.size + y.size)
    }

    private fun grams(s: String): List<String> = if (s.length < 2) listOf(s) else s.windowed(2)
}
