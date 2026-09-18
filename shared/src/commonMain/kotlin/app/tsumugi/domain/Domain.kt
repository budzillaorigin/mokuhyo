package app.tsumugi.domain

/** BRIEF §5.1. Stored as the enum name in TEXT columns. */
enum class ItemKind(val label: String) {
    RADICAL("Radical"), KANJI("Kanji"), VOCAB("Vocabulary"), GRAMMAR("Grammar"), SENTENCE("Sentence"),
    LISTENING("Listening"), WRITING("Writing"), MINIMAL_PAIR("Minimal pair"), CUSTOM("Card"),
}

/** Where an item came from. `LLM` content must show an "AI-generated" badge (CLAUDE.md rule 10). */
enum class ItemSource(val code: String) {
    PACK("pack"), WANIKANI("wanikani"), ANKI("anki"), USER("user"), LLM("llm"), VERIFIED("verified");

    companion object {
        fun of(code: String): ItemSource = entries.first { it.code == code }
    }
}

/**
 * What a card asks. Kanji-path items use MEANING/READING (typed answers, WaniKani-style); flashcards imported
 * from Anki use RECOGNITION/RECALL (self-graded); the rest arrive with their features.
 */
enum class CardDirection { RECOGNITION, RECALL, MEANING, READING, WRITING, LISTENING, CLOZE, PRODUCTION }

enum class RelationKind { COMPONENT, USES_KANJI, GRAMMAR_EXAMPLE, SIMILAR, CONFUSED_WITH }

enum class SessionKind { TODAY, REVIEW, LESSON, CONVERSATION, EXAM, WRITING, LISTENING, READING }

enum class IntegrationKind { WANIKANI, BUNPRO, NOTION, LLM_ENDPOINT, VOICEVOX, SYNC }

/**
 * WaniKani-style familiarity names over FSRS stability (DECISIONS D-017). Unlocks key off [GURU].
 * Thresholds are in days of stability, i.e. the interval at which recall probability falls to 90%.
 */
enum class Stage(val minStabilityDays: Double, val label: String) {
    APPRENTICE(0.0, "Apprentice"), GURU(3.0, "Guru"), MASTER(21.0, "Master"), ENLIGHTENED(60.0, "Enlightened"), BURNED(180.0, "Burned");

    companion object {
        fun of(stabilityDays: Double?, started: Boolean): Stage? {
            if (!started) return null
            val s = stabilityDays ?: return APPRENTICE
            return entries.last { s >= it.minStabilityDays }
        }
    }
}
