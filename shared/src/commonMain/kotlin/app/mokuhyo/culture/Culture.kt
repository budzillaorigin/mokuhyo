package app.mokuhyo.culture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * Culture cards (BRIEF_PHASE8 §B.4.1): `packs/<lang>/culture.json`. Original wording informed by one section of the
 * AFCLC Expeditionary Culture Field Guide for the partner country, cited by chapter and page; cards without a guide
 * (Germany, France, Qatar notes) say so in [CardSource.doc] and carry no section.
 */
@Serializable
data class CultureCard(
    val id: String,
    val lang: String,
    val country: String,
    val tags: List<String>,
    val title: String,
    val body: String,
    val doThis: List<String> = emptyList(),
    val avoidThis: List<String> = emptyList(),
    val source: CardSource,
    val verified: Boolean = false,
) {
    val cited: Boolean get() = source.sourceId.isNotEmpty()

    fun citation(): String = if (cited) "${source.doc} — ${source.section}, p. ${source.page}" else source.doc
}

@Serializable
data class CardSource(val doc: String, val sourceId: String = "", val section: String = "", val page: String = "")

@Serializable
data class CulturePack(val format: String = "mokuhyo-culture/1", val lang: String, val country: String = "", val license: String = "", val cards: List<CultureCard> = emptyList()) {
    /** Cards whose tags meet [tags], most matching tags first. */
    fun forTags(tags: Collection<String>, limit: Int = 4): List<CultureCard> =
        cards.map { it to it.tags.count { t -> t in tags } }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }.take(limit)

    companion object {
        fun parse(text: String): CulturePack = json.decodeFromString(serializer(), text)
    }
}

/**
 * The pragmatics pack (BRIEF_PHASE8 §B.4.4): `packs/<lang>/pragmatics.json`. Entries per topic: address and rank,
 * indirectness and refusals, apology and thanks, small talk, disagreement in meetings, hospitality, gestures and
 * silence — each with a rule and examples of what to say, what not to say and why.
 */
@Serializable
data class PragmaticsPack(val format: String = "mokuhyo-pragmatics/1", val lang: String, val license: String = "", val entries: List<PragmaticsEntry> = emptyList()) {
    fun byTopic(topic: String): List<PragmaticsEntry> = entries.filter { it.topic == topic }

    fun ids(ids: Collection<String>): List<PragmaticsEntry> = entries.filter { it.id in ids }

    companion object {
        val TOPICS = listOf(
            "address" to "Address and rank etiquette", "refusal" to "Indirectness and refusals", "apology" to "Apology and thanks",
            "small_talk" to "Safe and taboo small talk", "disagreement" to "Disagreement in meetings", "hospitality" to "Hospitality obligations",
            "nonverbal" to "Gestures and silence",
        )

        fun parse(text: String): PragmaticsPack = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class PragmaticsEntry(
    val id: String,
    val topic: String,
    val rule: String,
    val examples: List<PragmaticsExample> = emptyList(),
    val source: CardSource = CardSource("AI-drafted"),
    val verified: Boolean = false,
)

/** [say] and [dontSay] are in the target language; [situation] and [why] in English. */
@Serializable
data class PragmaticsExample(val situation: String, val say: String, val dontSay: String = "", val why: String = "")

/**
 * A conversation partner (BRIEF_PHASE8 §B.4.2): `packs/<lang>/personas.json`, set in the partner country's air force
 * (D-028; Arabic has two sets, RSAF and QEAF). [patience] and [formality] are 1–5 and steer the prompt.
 */
@Serializable
data class Persona(
    val id: String,
    val lang: String,
    /** senior_counterpart | peer_officer | junior_enlisted | interpreter | local_contractor | civilian_official */
    val role: String,
    val name: String,
    /** Rank or title in the target language, as the learner should use it. */
    val rankTitle: String,
    val rankEnglish: String = "",
    val force: String,
    val gender: String = "male",
    /** How this persona speaks and expects to be spoken to (English, for the prompt and the picker). */
    val register: String,
    val patience: Int = 3,
    val formality: Int = 3,
    val bio: String = "",
    /** The persona's first line in the target language. */
    val greeting: String = "",
    val greetingEnglish: String = "",
    /** Pragmatics entry ids this persona enforces. */
    val pragmatics: List<String> = emptyList(),
    /** Culture card shown before talking with this persona. */
    val card: String = "",
    val source: String = "llm",
    val verified: Boolean = false,
) {
    val roleTitle: String get() = ROLES[role] ?: role

    companion object {
        val ROLES = mapOf(
            "senior_counterpart" to "Senior counterpart", "peer_officer" to "Peer officer", "junior_enlisted" to "Junior enlisted",
            "interpreter" to "Interpreter", "local_contractor" to "Local contractor", "civilian_official" to "Civilian official",
        )
    }
}

@Serializable
data class PersonaPack(val format: String = "mokuhyo-personas/1", val lang: String, val license: String = "", val personas: List<Persona> = emptyList()) {
    companion object {
        fun parse(text: String): PersonaPack = json.decodeFromString(serializer(), text)
    }
}
