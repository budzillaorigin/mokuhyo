package app.mokuhyo.opi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Language-specific interview knowledge (BRIEF §4 `opiProfile`), shipped in the pack's `opi.json` together with the
 * scripted fallback bank and the topic catalog. Prompts read it through `{register}` and `{language}` slots.
 */
@Serializable
data class OpiProfile(
    val language: String,
    /** How register works in this language and what the interviewer uses, in English, for the prompts. */
    val registerNotes: String,
    /** Formality markers a rater checks for (e.g. "です/ます", "vous", "Sie", "존댓말"). */
    val formalityMarkers: List<String> = emptyList(),
    /** Typical level-check topics per ILR level, English labels. */
    val levelCheckTopics: Map<String, List<String>> = emptyMap(),
    /** Role-play situations, English descriptions with the interviewer's role. */
    val rolePlaySeeds: List<String> = emptyList(),
)

/** One scripted question of the fallback bank (used with no model installed). */
@Serializable
data class BankQuestion(
    val id: String,
    val phase: String,
    val level: String,
    /** Interviewer question in the target language. */
    val prompt: String,
    /** English gloss, shown after the interview. */
    val english: String,
    val domain: String? = null,
    val source: String = "llm",
    val verified: Boolean = false,
)

/** A role-play card: situation in English for the learner, the interviewer's opening line in the language. */
@Serializable
data class RolePlay(
    val id: String,
    val level: String,
    val situation: String,
    val interviewerRole: String,
    val opening: String,
    val english: String,
    val domain: String? = null,
    val source: String = "llm",
    val verified: Boolean = false,
)

/** A conversation topic (BRIEF §6.3). [opener] is the partner's first line in the target language. */
@Serializable
data class Topic(
    val id: String,
    val domain: String,
    val title: String,
    val opener: String,
    val minLevel: String = "1",
    val source: String = "llm",
    val verified: Boolean = false,
)

/** The nine topic domains of BRIEF §6.3. */
enum class TopicDomain(val id: String, val title: String) {
    DAILY_LIFE("daily_life", "Daily life"),
    WORK_STUDY("work_study", "Work & study"),
    SOCIETY("society", "Society & current events"),
    TRAVEL_CULTURE("travel_culture", "Travel & culture"),
    MILITARY_GARRISON("military_garrison", "Military — garrison"),
    MILITARY_OPERATIONS("military_operations", "Military — operations"),
    MILITARY_FIELD("military_field", "Military — field situations"),
    INTERPRETER("interpreter", "Interpreter / liaison"),
    ABSTRACT("abstract", "Abstract & hypothetical"),
    ;

    companion object {
        fun of(id: String): TopicDomain? = entries.firstOrNull { it.id == id }
    }
}

/** The pack file `packs/<lang>/opi.json`. */
@Serializable
data class OpiPack(
    val language: String,
    val profile: OpiProfile,
    val questions: List<BankQuestion> = emptyList(),
    val rolePlays: List<RolePlay> = emptyList(),
    val topics: List<Topic> = emptyList(),
    /** ILR self-rating checklist (public-domain ILR speaking descriptors, paraphrased), per level. */
    val checklist: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): OpiPack = json.decodeFromString(serializer(), text)
    }
}
