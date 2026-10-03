package app.mokuhyo.lexicon

import app.mokuhyo.exam.ExamKind
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.ScriptLine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A topic track (BRIEF_PHASE8 §B.3): the pack file `packs/<lang>/track-<id>.json`, built by tools/tracks/build_track.py.
 * Terms carry their provenance: the English definition's source and page (US public domain, or `authored`), and each
 * target-language equivalent's allied source and page (empty = model-proposed, badge `unconfirmed-term`).
 */
@Serializable
data class Track(
    val format: String = "mokuhyo-track/1",
    val id: String,
    val lang: String,
    val title: String,
    val version: String,
    val built: String = "",
    val license: String = "",
    val attribution: String = "",
    val terms: List<TrackTerm> = emptyList(),
    val drills: List<Drill> = emptyList(),
    val scenarios: List<Scenario> = emptyList(),
    val dialogues: List<Dialogue> = emptyList(),
    /** SOURCES.json id → "Title (edition)" for the sources the terms cite. */
    val sources: Map<String, String> = emptyMap(),
) {
    fun term(id: String): TrackTerm? = terms.firstOrNull { it.id == id }

    val domains: List<String> get() = terms.map { it.domain }.distinct()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Track = json.decodeFromString(serializer(), text)

        /** Display names for the seed list's domains. */
        val DOMAIN_TITLES = mapOf(
            "cuas" to "Counter-UAS", "base-defense" to "Base defense", "airspace" to "Airspace & air defense", "ew" to "Electromagnetic warfare",
            "roe" to "Rules of engagement", "c2" to "Command & control", "logistics" to "Logistics", "medical" to "Medical",
            "hadr" to "Humanitarian & disaster relief", "brevity" to "Radio brevity",
        )
    }
}

object TrackIds {
    const val CUAS = "cuas-base-defense"
}

@Serializable
data class Citation(val doc: String, val page: String = "", val sourceId: String = "")

/** One target-language rendering of a term. [kind]: native | calque | loanword | acronym. Empty [source] = model-proposed. */
@Serializable
data class Equivalent(
    val text: String,
    val kind: String = "calque",
    val source: String = "",
    val page: String = "",
    val verified: Boolean = false,
    /** True for the English brevity word when the partner force uses it on the radio. */
    val radio: Boolean = false,
)

@Serializable
data class Example(val text: String, val english: String = "")

@Serializable
data class TrackTerm(
    val id: String,
    val domain: String,
    val priority: Int = 2,
    val termEn: String,
    val acronym: String = "",
    val definitionEn: String,
    val definitionEnSource: Citation = Citation("authored"),
    val term: String,
    val termKind: String = "",
    val radioEnglish: Boolean = false,
    val equivalents: List<Equivalent> = emptyList(),
    val definition: String = "",
    /** draft | checked | approved (docs/TERM_PIPELINE.md). */
    val status: String = "draft",
    /** "" | unreviewed | unconfirmed-term — shown in the app until a reviewer approves the row. */
    val badge: String = "unreviewed",
    val registerNote: String = "",
    val examples: List<Example> = emptyList(),
    val collocations: List<String> = emptyList(),
    val source: String = "llm",
    val verified: Boolean = false,
) {
    val englishIsOriginal: Boolean get() = definitionEnSource.doc == "authored"

    val termConfirmed: Boolean get() = equivalents.firstOrNull()?.source?.isNotEmpty() == true

    /** The badge text the UI shows (CLAUDE.md rule 7: no unmarked invented term). */
    val badgeLabel: String? get() = when {
        status == "approved" && badge.isEmpty() -> null
        badge == "unconfirmed-term" -> "Unconfirmed term · AI-proposed"
        else -> "AI-drafted · unreviewed"
    }

    fun englishCitation(): String = if (englishIsOriginal) "Original definition (no public US source defines it)"
        else "${definitionEnSource.doc}, p. ${definitionEnSource.page}"
}

/** kind: meaning | fill_in | register | brevity. Brevity drills have no choices; [expected] is the radio form. */
@Serializable
data class Drill(
    val id: String,
    val kind: String,
    val termId: String = "",
    val prompt: String,
    val choices: List<String> = emptyList(),
    val answer: Int = 0,
    val expected: String = "",
    val explanation: String = "",
)

@Serializable
data class Scenario(
    val id: String,
    val title: String,
    val level: String = "2",
    val situation: String,
    val learnerRole: String,
    val partnerRole: String,
    /** Culture-card tags (BRIEF_PHASE8 §B.4.1): rank, hospitality, refusal, time, gender, religion, gift, meal, meeting, radio, gate. */
    val tags: List<String> = emptyList(),
    val terms: List<String> = emptyList(),
    val opener: String = "",
    val openerEnglish: String = "",
    val source: String = "llm",
    val verified: Boolean = false,
)

@Serializable
data class DialogueLine(val speaker: String, val voice: String = "female", val text: String, val english: String = "")

@Serializable
data class Dialogue(
    val id: String,
    val scenario: String = "",
    val title: String,
    val level: String = "2",
    val lines: List<DialogueLine>,
    /** Pre-rendered clip relative to the pack dir, when the pack has one. */
    val audio: String? = null,
    val source: String = "llm",
    val verified: Boolean = false,
) {
    /** As a listening passage, so the existing audio path (pre-rendered clip or the voice service) plays it. */
    fun asPassage(lang: String): ExamPassage = ExamPassage(
        id = id, exam = ExamKind.DLPT_LISTENING, language = lang, level = level, textType = "dialogue", title = title, body = "",
        script = lines.map { ScriptLine(it.speaker, it.voice, it.text) }, source = source, verified = verified, audio = audio,
    )
}

/** Grades a drill answer: the choice index, or for brevity the typed radio form (folded comparison by the caller). */
object Drills {
    fun correct(drill: Drill, choice: Int): Boolean = drill.choices.isNotEmpty() && choice == drill.answer

    fun correctTyped(drill: Drill, typed: String, fold: (String) -> String): Boolean =
        typed.isNotBlank() && fold(typed.trim()) == fold(drill.expected.trim())
}
