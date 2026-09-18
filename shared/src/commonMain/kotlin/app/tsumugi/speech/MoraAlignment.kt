package app.tsumugi.speech

import app.tsumugi.jp.Kana
import app.tsumugi.jp.Mora

enum class MoraOutcome { HIT, SUBSTITUTED, MISSED }

/** One target mora and what was heard in its place (null = nothing, i.e. MISSED). */
data class MoraMatch(val target: String, val heard: String?, val outcome: MoraOutcome) {
    /** A substitution learners commonly make (voicing, vowel length, っ, ん, ら/だ) — gentler feedback. */
    val commonConfusion: Boolean get() = heard != null && outcome == MoraOutcome.SUBSTITUTED && MoraAlignment.similar(target, heard)
}

data class MoraAlignmentResult(
    val matches: List<MoraMatch>,
    /** Morae heard that don't correspond to anything in the target. */
    val insertions: List<String>,
) {
    val accuracy: Double get() = if (matches.isEmpty()) 0.0 else matches.count { it.outcome == MoraOutcome.HIT }.toDouble() / matches.size
}

/**
 * Aligns what speech recognition heard with the target reading, mora by mora (Needleman–Wunsch). Costs are
 * tuned for common learner confusions: dropping or adding a long vowel or っ is cheap, voicing pairs (か/が),
 * ん vs む/ぬ and ら行 vs だ行 are cheap substitutions. This compares recognized kana, not sounds: it can only
 * say which morae the recognizer heard differently.
 */
object MoraAlignment {
    private const val GAP = 1.0
    private const val CHEAP_GAP = 0.6
    private const val SUBSTITUTE = 1.3
    private const val SIMILAR = 0.5

    /**
     * [heard] may be kana or mixed text; [reading] converts mixed text (kanji) to kana, e.g. via the dictionary
     * tokenizer. Without it, non-kana characters are dropped.
     */
    fun align(target: String, heard: String, reading: ((String) -> String)? = null): MoraAlignmentResult {
        val t = Mora.split(normalize(target))
        val h = Mora.split(normalize(reading?.invoke(heard) ?: heard))
        val n = t.size
        val m = h.size
        val cost = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) cost[i][0] = cost[i - 1][0] + gapCost(t[i - 1], t.getOrNull(i - 2))
        for (j in 1..m) cost[0][j] = cost[0][j - 1] + gapCost(h[j - 1], h.getOrNull(j - 2))
        for (i in 1..n) for (j in 1..m) {
            cost[i][j] = minOf(
                cost[i - 1][j - 1] + substitutionCost(t[i - 1], h[j - 1]),
                cost[i - 1][j] + gapCost(t[i - 1], t.getOrNull(i - 2)),
                cost[i][j - 1] + gapCost(h[j - 1], h.getOrNull(j - 2)),
            )
        }
        // Trace back.
        val matches = ArrayList<MoraMatch>()
        val insertions = ArrayList<String>()
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + substitutionCost(t[i - 1], h[j - 1]) -> {
                    val outcome = if (t[i - 1] == h[j - 1]) MoraOutcome.HIT else MoraOutcome.SUBSTITUTED
                    matches += MoraMatch(t[i - 1], h[j - 1], outcome); i--; j--
                }
                i > 0 && cost[i][j] == cost[i - 1][j] + gapCost(t[i - 1], t.getOrNull(i - 2)) -> {
                    matches += MoraMatch(t[i - 1], null, MoraOutcome.MISSED); i--
                }
                else -> { insertions += h[j - 1]; j-- }
            }
        }
        return MoraAlignmentResult(matches.reversed(), insertions.reversed())
    }

    internal fun normalize(text: String): String = Kana.toHiragana(text).filter { Kana.isKana(it) }

    private fun substitutionCost(a: String, b: String): Double = when {
        a == b -> 0.0
        similar(a, b) -> SIMILAR
        else -> SUBSTITUTE
    }

    /** Dropping っ, ー, ん or a vowel that only lengthens the previous mora is a cheap, common slip. */
    private fun gapCost(mora: String, previous: String?): Double =
        if (mora == "っ" || mora == "ー" || mora == "ん" || lengthens(previous, mora)) CHEAP_GAP else GAP

    private fun lengthens(previous: String?, mora: String): Boolean {
        val vowel = previous?.let { vowelOf(it) } ?: return false
        return (mora == "あ" && vowel == 'a') || (mora == "い" && (vowel == 'i' || vowel == 'e')) ||
            (mora == "う" && (vowel == 'u' || vowel == 'o')) || (mora == "え" && vowel == 'e') || (mora == "お" && vowel == 'o')
    }

    internal fun similar(a: String, b: String): Boolean {
        if (a.length != b.length || a.isEmpty()) return false
        if (devoice(a) == devoice(b)) return true
        val pair = setOf(a, b)
        return pair in NASAL_PAIRS || pair in RA_DA_PAIRS || (a in LONG && b in LONG)
    }

    private fun devoice(mora: String): String = buildString { mora.forEach { append(DEVOICE[it] ?: it) } }

    private fun vowelOf(mora: String): Char? = VOWELS.entries.firstOrNull { (_, row) -> mora.last() in row }?.key

    private val DEVOICE: Map<Char, Char> = run {
        val voiced = "がぎぐげござじずぜぞだぢづでどばびぶべぼぱぴぷぺぽゔ"
        val plain = "かきくけこさしすせそたちつてとはひふへほはひふへほう"
        voiced.indices.associate { voiced[it] to plain[it] }
    }
    private val NASAL_PAIRS = listOf("む", "ぬ", "の", "に").map { setOf("ん", it) }.toSet()
    private val RA_DA_PAIRS = listOf("ら" to "だ", "り" to "ぢ", "る" to "づ", "れ" to "で", "ろ" to "ど").map { setOf(it.first, it.second) }.toSet()
    private val LONG = setOf("ー", "う", "い", "あ", "え", "お")
    private val VOWELS = mapOf(
        'a' to "あかがさざただなはばぱまやらわゃ",
        'i' to "いきぎしじちぢにひびぴみり",
        'u' to "うくぐすずつづぬふぶぷむゆるゅゔ",
        'e' to "えけげせぜてでねへべぺめれ",
        'o' to "おこごそぞとどのほぼぽもよろをょ",
    )
}
