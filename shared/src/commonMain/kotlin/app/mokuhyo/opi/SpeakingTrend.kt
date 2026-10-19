package app.mokuhyo.opi

import kotlinx.serialization.Serializable

/**
 * Whether an interview's rating joins the learner's speaking trend, and how firmly (QC 2026-10-19 #3; CLAUDE.md
 * rule 7). Only a model-rated test counts. Typed answers are writing, not speaking: an interview answered mostly by
 * typing, or one that fell back to showing the questions as text, stays out of the oral trend. A rating counts as firm
 * only when the language and tier are calibrated against instructor-rated samples and every answer was spoken;
 * anything else is provisional, with lower confidence, and Home, History and the PDF report say so.
 */
object SpeakingTrend {
    data class Entry(val confidence: Double, val provisional: Boolean)

    /** Where an interview stands in the speaking trend; stored with the conversation (QC follow-up F4). */
    @Serializable
    enum class Status { COUNTED_FIRM, COUNTED_PROVISIONAL, EXCLUDED, NOT_APPLICABLE }

    /** [status] and its short reason ("not calibrated", "mostly typed", "practice", …). */
    data class Decision(val status: Status, val reason: String?)

    /** How an interview's rating is shown, the same everywhere (History, the PDF, the results screen). */
    data class Label(val text: String, val provisional: Boolean)

    /** The label part for an interview whose questions were mostly scripted (BRIEF_INTERVIEW_SPEED N5). */
    const val GUIDED = "guided interview — scripted questions"

    /** At least this share of scripted questions makes a guided interview: out of the trend. */
    const val MOSTLY_GUIDED = 0.5

    /** At least this share (the N-00b target is under 10 %) makes a counted test provisional. */
    const val PARTLY_GUIDED = 0.1

    private fun share(guided: Int, questions: Int) = if (questions > 0) guided.toDouble() / questions else 0.0

    /**
     * [entry] and [exclusion] as one stored decision; [rated] is false when the interview has no estimate at all.
     * [guided] of [questions] came from the scripted bank (no model, or the model missed its deadline, N5): a mostly
     * guided interview is excluded, a partly guided test provisional, and the reason says so.
     */
    fun decide(
        test: Boolean, modelRated: Boolean, spoken: Int, typed: Int, textFallback: Boolean, calibrated: Boolean, rated: Boolean = true,
        guided: Int = 0, questions: Int = 0,
    ): Decision {
        val share = share(guided, questions)
        val note = GUIDED.takeIf { share >= MOSTLY_GUIDED }
        fun excluded(reason: String) = Decision(Status.EXCLUDED, listOfNotNull(reason, note).joinToString("; "))
        return when {
            !rated -> Decision(Status.NOT_APPLICABLE, note)
            !test -> excluded("practice")
            !modelRated -> excluded("self-rated")
            textFallback -> excluded("text practice")
            spoken == 0 || typed > spoken -> excluded("mostly typed")
            note != null -> Decision(Status.EXCLUDED, GUIDED)
            !calibrated -> Decision(Status.COUNTED_PROVISIONAL, if (share >= PARTLY_GUIDED) "not calibrated; partly guided" else "not calibrated")
            share >= PARTLY_GUIDED -> Decision(Status.COUNTED_PROVISIONAL, "partly guided")
            typed > 0 -> Decision(Status.COUNTED_PROVISIONAL, "some answers typed")
            else -> Decision(Status.COUNTED_FIRM, null)
        }
    }

    /**
     * The label for an interview rated [estimate]. [status]/[reason] are what was stored with it; older records have
     * neither, and then [trendRow] decides: the provisional flag of its speaking-estimate row, or null when it has
     * none (so it isn't in the trend). Older records are never rewritten (rule 5); only their label is worked out.
     */
    fun label(kind: String, estimate: String, modelRated: Boolean, selfRated: Boolean, status: Status?, reason: String?, trendRow: Boolean?): Label {
        val ilr = "ILR $estimate"
        if (selfRated) return Label("$ilr (self-rated${if (reason?.contains(GUIDED) == true) " — $GUIDED" else ""})", false)
        val rater = if (modelRated) "AI-rated" else "rated"
        if (kind != "OPI" && kind != "OPI_TEST") return Label(if (modelRated) "$ilr (AI-rated)" else ilr, false)
        val dash = reason?.let { " — $it" } ?: ""
        return when (status) {
            Status.COUNTED_FIRM -> Label("$ilr ($rater)", false)
            Status.COUNTED_PROVISIONAL -> Label("$ilr* ($rater, provisional$dash)", true)
            Status.EXCLUDED -> Label("$ilr ($rater, not in speaking trend$dash)", false)
            Status.NOT_APPLICABLE -> Label("$ilr ($rater)", false)
            null -> when {
                trendRow == true -> Label("$ilr* ($rater, provisional)", true)
                trendRow == false -> Label("$ilr ($rater)", false)
                kind == "OPI_TEST" && modelRated -> Label("$ilr ($rater, not in speaking trend)", false)
                kind == "OPI" -> Label("$ilr ($rater, not in speaking trend — practice)", false)
                else -> Label("$ilr ($rater; recorded before 2026-10-19, trend status unknown)", false)
            }
        }
    }

    /** What the `*` after a level means in the PDF: on the cover and under provisional interview entries. */
    const val PROVISIONAL_NOTE = "* provisional: a short or incomplete test form, or a speaking rating by the AI model that hasn't been calibrated " +
        "against instructor ratings for that language (or had some answers typed)."

    /** Why an interview didn't join the trend, for the results screen; null when it did. */
    fun exclusion(test: Boolean, modelRated: Boolean, spoken: Int, typed: Int, textFallback: Boolean, guided: Int = 0, questions: Int = 0): String? = when {
        !test -> "Practice interviews don't count toward your speaking estimate."
        !modelRated -> "Self-ratings don't count toward your speaking estimate."
        textFallback -> "The questions were shown as text because the voice couldn't play, so this interview doesn't count toward your speaking estimate."
        spoken == 0 || typed > spoken -> "Most answers were typed, so this interview doesn't count toward your speaking estimate (typing is not speaking)."
        share(guided, questions) >= MOSTLY_GUIDED -> "Most questions were scripted (a guided interview: the AI model wasn't fast enough or isn't installed), " +
            "so this interview doesn't count toward your speaking estimate."
        else -> null
    }

    fun entry(test: Boolean, modelRated: Boolean, spoken: Int, typed: Int, textFallback: Boolean, calibrated: Boolean, guided: Int = 0, questions: Int = 0): Entry? {
        if (exclusion(test, modelRated, spoken, typed, textFallback, guided, questions) != null) return null
        val provisional = !calibrated || typed > 0 || share(guided, questions) >= PARTLY_GUIDED
        return Entry(if (provisional) 0.3 else 0.6, provisional)
    }
}
