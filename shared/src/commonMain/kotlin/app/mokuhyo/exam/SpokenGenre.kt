package app.mokuhyo.exam

/**
 * How a listening script should sound (QC 2026-10-30 V9): spoken genres, not the written "authentic document"
 * instruction reading passages get. A prepared genre (briefing, news, speech…) may sound prepared; a discussion genre
 * (conversation, interview, phone call…) needs responsive turns. Either way the model is told the speakers'
 * relationship, purpose, register and situation, and not to add fillers, pause tags or invented breaths, which some
 * engines read aloud. tools/items/gen_dlpt.py keeps the same lists.
 */
object SpokenGenre {
    /** Text types where people talk with each other. Everything else is one prepared voice addressing listeners. */
    val DISCUSSION = setOf("conversation", "debate", "interview", "panel_discussion", "phone_call", "short_exchange", "voicemail")

    fun isDiscussion(textType: String): Boolean = textType in DISCUSSION

    /** The spoken-genre instruction for a listening script of [textType] in [language] (an English language name). */
    fun instruction(textType: String, language: String): String {
        val kind = textType.replace('_', ' ')
        val genre = if (isDiscussion(textType)) {
            "This is a spoken $kind between native speakers of $language, not a written text read aloud. Decide who " +
                "the speakers are to each other (colleagues, neighbors, a clerk and a customer…), why they are talking " +
                "and where, and let that relationship set the register (formal or informal address, politeness) " +
                "consistently. Use responsive turns of uneven length: short reactions, answers to what was just asked, " +
                "an occasional longer explanation, and ordinary spoken syntax."
        } else {
            "This is a prepared spoken $kind in $language, not a written text read aloud. Decide who is speaking to " +
                "whom, for what purpose and in what situation, and use the register that situation calls for. It may " +
                "sound prepared, with clear structure, but in sentences a speaker would say aloud."
        }
        return "$genre Keep grammar and register native at every level; simplify ideas and vocabulary for the level " +
            "instead. Write only the spoken words: no fillers added for effect, no stage directions, no [pause] tags, " +
            "no ellipses for pauses and no invented breaths."
    }
}
