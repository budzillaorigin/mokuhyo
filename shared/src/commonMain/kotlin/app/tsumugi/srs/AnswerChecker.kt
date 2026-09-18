package app.tsumugi.srs

import app.tsumugi.jp.Kana
import app.tsumugi.jp.Romaji
import app.tsumugi.platform.normalizeNfc

enum class Verdict {
    CORRECT,
    /** Accepted, but not an exact match (typo within tolerance) — the UI shows the intended answer. */
    CLOSE,
    WRONG,
    /** A valid reading of the wrong kind (e.g. on'yomi when kun'yomi was asked): shake, don't count. */
    WRONG_KIND,
}

data class CheckResult(val verdict: Verdict, val matched: String? = null) {
    val accepted: Boolean get() = verdict == Verdict.CORRECT || verdict == Verdict.CLOSE
}

/** WaniKani-flavoured answer checking (BRIEF §5.4). Pure functions, fully tested. */
object AnswerChecker {

    /**
     * Meaning answers: case/punctuation-insensitive, ignores leading "to "/"a "/"the " and parentheses, accepts
     * user synonyms, and tolerates one edit (Damerau–Levenshtein ≤ 1) for answers of 5+ characters.
     */
    fun checkMeaning(answer: String, meanings: List<String>, synonyms: List<String> = emptyList()): CheckResult {
        val given = normalizeMeaning(answer)
        if (given.isEmpty()) return CheckResult(Verdict.WRONG)
        val targets = (meanings + synonyms).map { it to normalizeMeaning(it) }.filter { it.second.isNotEmpty() }
        targets.firstOrNull { it.second == given }?.let { return CheckResult(Verdict.CORRECT, it.first) }
        if (given.length >= 5) {
            targets.firstOrNull { it.second.length >= 5 && damerauLevenshtein(given, it.second) <= 1 }
                ?.let { return CheckResult(Verdict.CLOSE, it.first) }
        }
        return CheckResult(Verdict.WRONG)
    }

    /**
     * Reading answers must match exactly (after romaji→kana conversion and katakana folding). [otherReadings]
     * are valid readings that weren't asked for (e.g. kun'yomi when the path teaches on'yomi) → [Verdict.WRONG_KIND].
     */
    fun checkReading(answer: String, readings: List<String>, otherReadings: List<String> = emptyList()): CheckResult {
        val given = normalizeReading(answer)
        if (given.isEmpty()) return CheckResult(Verdict.WRONG)
        readings.firstOrNull { normalizeReading(it) == given }?.let { return CheckResult(Verdict.CORRECT, it) }
        if (otherReadings.any { normalizeReading(it) == given }) return CheckResult(Verdict.WRONG_KIND)
        return CheckResult(Verdict.WRONG)
    }

    /**
     * Meaning answers compare after: NFC, full-width ASCII → ASCII (ｃａｆé → café) and the ideographic space →
     * space, lowercase, parenthesized notes removed, and anything that isn't a letter or digit of **any** script
     * (plus apostrophes and combining marks) turned into a space (F-35). Accents stay (café ≠ cafe; one edit away
     * still counts as close), kana and kanji stay (Japanese synonyms), and no NFKC: this is answer matching, not a
     * dictionary key, and NFKC would also fold ㌔ or half-width kana in surprising ways (CLAUDE.md rule 7).
     */
    internal fun normalizeMeaning(s: String): String {
        val folded = buildString(s.length) {
            for (c in normalizeNfc(s)) {
                append(
                    when (c) {
                        in '！'..'～' -> c - 0xFEE0
                        '　' -> ' '
                        else -> c
                    },
                )
            }
        }.lowercase().replace(PARENTHESIZED, " ")
        val kept = buildString(folded.length) {
            for (c in folded) {
                append(if (c.isLetterOrDigit() || c == '\'' || c.isSurrogate() || c.category == CharCategory.NON_SPACING_MARK) c else ' ')
            }
        }
        return kept.replace(WHITESPACE, " ")
            .trim()
            .removePrefix("to ").removePrefix("a ").removePrefix("an ").removePrefix("the ")
            .trim()
    }

    private val PARENTHESIZED = Regex("\\([^)]*\\)")
    private val WHITESPACE = Regex("\\s+")

    /** Kana readings in KANJIDIC style ("た.べる", "-か", "ショク") reduce to plain hiragana. */
    internal fun normalizeReading(s: String): String {
        val kana = if (s.any { it in 'a'..'z' || it in 'A'..'Z' }) Romaji.finalize(Romaji.toHiragana(s.lowercase())) else s
        return Kana.toHiragana(kana).filter { it != '.' && it != '-' && it != ' ' && it != '・' }
    }

    /** Optimal string alignment distance (adjacent transpositions count as one edit). */
    fun damerauLevenshtein(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.length][b.length]
    }
}
