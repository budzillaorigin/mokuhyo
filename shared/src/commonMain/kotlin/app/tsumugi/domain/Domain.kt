package app.tsumugi.domain

/** BRIEF §5.1. Stored as the enum name in TEXT columns. */
enum class ItemKind(val label: String) {
    RADICAL("Radical"), KANJI("Kanji"), VOCAB("Vocabulary"), GRAMMAR("Grammar"), SENTENCE("Sentence"),
    LISTENING("Listening"), WRITING("Writing"), MINIMAL_PAIR("Minimal pair"), CUSTOM("Card"),

    /** A hiragana or katakana character of the kana course (BRIEF_V2 G-13, D-117). */
    KANA("Kana"),
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
enum class CardDirection {
    RECOGNITION, RECALL, MEANING, READING, WRITING, LISTENING, CLOZE, PRODUCTION,

    /** Bunpro-style "ghost": a short-lived extra grammar card spawned by a miss, retired after two correct answers. */
    GHOST,
}

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
        fun of(stabilityDays: Double?, started: Boolean): Stage? = if (started) started(stabilityDays) else null

        /**
         * Stage of a started card. Total: a card with no stability yet (introduced, or LEARNING before its first
         * graded answer) and any out-of-range value (NaN, negative) is [APPRENTICE] (BRIEF_V2 F-17).
         */
        fun started(stabilityDays: Double?): Stage {
            val s = stabilityDays ?: return APPRENTICE
            return entries.lastOrNull { s >= it.minStabilityDays } ?: APPRENTICE
        }
    }
}
