package app.mokuhyo.opi

/**
 * Whether an interview's rating joins the learner's speaking trend, and how firmly (QC 2026-10-19 #3; CLAUDE.md
 * rule 7). Only a model-rated test counts. Typed answers are writing, not speaking: an interview answered mostly by
 * typing, or one that fell back to showing the questions as text, stays out of the oral trend. A rating counts as firm
 * only when the language and tier are calibrated against instructor-rated samples and every answer was spoken;
 * anything else is provisional, with lower confidence, and Home, History and the PDF report say so.
 */
object SpeakingTrend {
    data class Entry(val confidence: Double, val provisional: Boolean)

    /** Why an interview didn't join the trend, for the results screen; null when it did. */
    fun exclusion(test: Boolean, modelRated: Boolean, spoken: Int, typed: Int, textFallback: Boolean): String? = when {
        !test -> "Practice interviews don't count toward your speaking estimate."
        !modelRated -> "Self-ratings don't count toward your speaking estimate."
        textFallback -> "The questions were shown as text because the voice couldn't play, so this interview doesn't count toward your speaking estimate."
        spoken == 0 || typed > spoken -> "Most answers were typed, so this interview doesn't count toward your speaking estimate (typing is not speaking)."
        else -> null
    }

    fun entry(test: Boolean, modelRated: Boolean, spoken: Int, typed: Int, textFallback: Boolean, calibrated: Boolean): Entry? {
        if (exclusion(test, modelRated, spoken, typed, textFallback) != null) return null
        val provisional = !calibrated || typed > 0
        return Entry(if (provisional) 0.3 else 0.6, provisional)
    }
}
