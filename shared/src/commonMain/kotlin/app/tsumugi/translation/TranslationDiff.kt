package app.tsumugi.translation

import app.tsumugi.jp.Kana

/** SAME: in both; MISSING: only in the reference; EXTRA: only in the learner's translation. */
enum class DiffKind { SAME, MISSING, EXTRA }

data class DiffSegment(val kind: DiffKind, val text: String)

/**
 * A word-level (English) or character-level (Japanese) diff between the learner's translation and the reference, for
 * the diff view (BRIEF_V2 §6.12, D-271). Deterministic and offline, so it shows with or without a model. Punctuation
 * and case are ignored when matching (sight-translation transcripts have neither), and shown with the unit before them.
 */
data class TranslationDiff(val segments: List<DiffSegment>, val matched: Int, val referenceUnits: Int, val attemptUnits: Int) {
    /** Share of the reference's units the learner also produced (a rough overlap, not a grade). */
    val overlap: Double get() = if (referenceUnits == 0) 0.0 else matched.toDouble() / referenceUnits

    companion object {
        private const val MAX_UNITS = 1500

        fun of(attempt: String, reference: String): TranslationDiff {
            val japanese = Kana.containsKanji(reference + attempt) || (reference + attempt).any { Kana.isKana(it) }
            val a = units(attempt, japanese)
            val r = units(reference, japanese)
            if (a.size > MAX_UNITS || r.size > MAX_UNITS) {
                return TranslationDiff(
                    listOf(DiffSegment(DiffKind.EXTRA, attempt), DiffSegment(DiffKind.MISSING, reference)), 0, r.size, a.size,
                )
            }
            // LCS table over the comparison keys.
            val n = a.size
            val m = r.size
            val dp = Array(n + 1) { IntArray(m + 1) }
            for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i].key == r[j].key) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
            val out = ArrayList<Pair<DiffKind, String>>()
            var i = 0
            var j = 0
            while (i < n || j < m) {
                when {
                    i < n && j < m && a[i].key == r[j].key -> { out += DiffKind.SAME to r[j].shown; i++; j++ }
                    j < m && (i == n || dp[i][j + 1] >= dp[i + 1][j]) -> { out += DiffKind.MISSING to r[j].shown; j++ }
                    else -> { out += DiffKind.EXTRA to a[i].shown; i++ }
                }
            }
            val sep = if (japanese) "" else " "
            val merged = ArrayList<DiffSegment>()
            for ((kind, text) in out) {
                val last = merged.lastOrNull()
                if (last != null && last.kind == kind) merged[merged.size - 1] = last.copy(text = last.text + sep + text)
                else merged += DiffSegment(kind, text)
            }
            return TranslationDiff(merged, dp[0][0], m, n)
        }

        private data class Piece(val key: String, val shown: String)

        /** English: words (trailing punctuation shown with the word). Japanese: characters (punctuation shown, not matched). */
        private fun units(text: String, japanese: Boolean): List<Piece> {
            val out = ArrayList<Piece>()
            if (japanese) {
                var pending = ""
                for (c in text) {
                    if (c.isWhitespace()) continue
                    if (c.isLetterOrDigit() || Kana.isKana(c) || c == 'ー' || c == '々') {
                        out += Piece(Kana.toHiragana(c.toString()).lowercase(), pending + c)
                        pending = ""
                    } else if (out.isNotEmpty()) {
                        out[out.size - 1] = out.last().copy(shown = out.last().shown + c)
                    } else {
                        pending += c
                    }
                }
            } else {
                for (word in text.split(Regex("\\s+"))) {
                    val key = word.lowercase().filter { it.isLetterOrDigit() || it == '\'' }
                    if (key.isEmpty()) {
                        if (word.isNotEmpty() && out.isNotEmpty()) out[out.size - 1] = out.last().copy(shown = out.last().shown + " " + word)
                        continue
                    }
                    out += Piece(key, word)
                }
            }
            return out
        }
    }
}
