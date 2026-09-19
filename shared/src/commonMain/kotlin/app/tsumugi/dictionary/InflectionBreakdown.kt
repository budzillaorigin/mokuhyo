package app.tsumugi.dictionary

/** One inflection applied to a dictionary form: the Deinflector's [reason] code and a learner-facing [label]. */
data class InflectionStep(val reason: String, val label: String)

/**
 * The chip under a conjugated search hit (BRIEF_V2 §6.15, Lorenzi's Jisho): 食べさせられなかった = 食べる + causative +
 * passive + negative + past. Built from the Deinflector's rule chain ([SearchHit.deinflection], dictionary form
 * outward), so it shows exactly the analysis that found the word.
 */
data class InflectionBreakdown(val surface: String, val lemma: String, val steps: List<InflectionStep>) {
    /** "食べさせられなかった = 食べる + causative + passive + negative + past". */
    val text: String get() = "$surface = $lemma + " + steps.joinToString(" + ") { it.label }

    /** Just the chain, for a compact chip: "causative + passive + negative + past". */
    val chain: String get() = steps.joinToString(" + ") { it.label }

    companion object {
        /** Null when nothing was deinflected (an exact hit needs no chip) or the lemma is unknown. */
        fun of(surface: String, lemma: String?, reasons: List<String>): InflectionBreakdown? {
            if (reasons.isEmpty() || lemma.isNullOrEmpty() || surface == lemma) return null
            return InflectionBreakdown(surface, lemma, reasons.map { InflectionStep(it, label(it)) })
        }

        /**
         * Learner-facing names for the Deinflector's reason codes (DeinflectRules). Plain grammar terms stay as they
         * are; the "-x" codes of auxiliaries and te-form constructions show the Japanese ending. Unknown codes are shown
         * as they come, so a new rule never hides a step.
         */
        fun label(reason: String): String = LABELS[reason] ?: reason

        private val LABELS = mapOf(
            "short causative" to "causative (short)",
            "negative te" to "negative te-form",
            "te" to "te-form",
            "polite te" to "polite te-form",
            "polite invitation" to "polite invitation (ませんか)",
            "-tai" to "〜たい (want to)",
            "-nagara" to "〜ながら (while)",
            "-nasai" to "〜なさい (command)",
            "-sou" to "〜そう (looks like)",
            "-sugiru" to "〜すぎる (too much)",
            "-yasui" to "〜やすい (easy to)",
            "-nikui" to "〜にくい (hard to)",
            "-nakya" to "〜なきゃ (must)",
            "-nakucha" to "〜なくちゃ (must)",
            "-zu" to "〜ず (without)",
            "-zuni" to "〜ずに (without)",
            "-nu" to "〜ぬ (negative)",
            "-ba" to "〜ば (conditional)",
            "-tara" to "〜たら (conditional)",
            "-tari" to "〜たり (listing)",
            "-sa" to "〜さ (noun)",
            "-chau" to "〜ちゃう (completely)",
            "-teshimau" to "〜てしまう (completely)",
            "-teiku" to "〜ていく",
            "-tekuru" to "〜てくる",
            "-temiru" to "〜てみる (try)",
            "-teoku" to "〜ておく (in advance)",
            "-tearu" to "〜てある (resulting state)",
            "-temo" to "〜ても (even if)",
            "-teageru" to "〜てあげる (for someone)",
            "-tekureru" to "〜てくれる (for me)",
            "-temorau" to "〜てもらう (have done)",
            "-tekudasai" to "〜てください (please)",
        )
    }
}
