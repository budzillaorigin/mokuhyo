package app.mokuhyo.opi

import kotlinx.serialization.Serializable

/** Politeness level a role-play is conducted in. */
enum class Register { CASUAL, POLITE, KEIGO }

/** Structure of an OPI-style interview (BRIEF §5.10). */
enum class OpiPhase { WARM_UP, LEVEL_CHECK, PROBE, ROLE_PLAY, WIND_DOWN }

/**
 * DLI-style topic domains of OPI questions (BRIEF_V2 §6.16). The scripted interview rotates through them so a rating
 * rests on more than one kind of topic; [SITUATION] tags role-plays. Stored lowercase in the pack (`opi_question.domain`).
 */
enum class OpiDomain(val title: String) {
    PERSONAL("Personal"),
    FAMILY("Family"),
    WORK("Work"),
    DAILY_LIFE("Daily life"),
    TRAVEL("Travel"),
    CURRENT_EVENTS("Current events"),
    HYPOTHETICAL("Hypotheticals"),
    ABSTRACT("Abstract topics"),
    SITUATION("Situation"),
    ;

    companion object {
        fun parse(value: String?): OpiDomain? =
            value?.takeIf { it.isNotBlank() }?.let { v -> entries.firstOrNull { it.name.equals(v, ignoreCase = true) } }

        /** The five domains the DLI interview protocol samples. */
        val dli: List<OpiDomain> = listOf(FAMILY, WORK, CURRENT_EVENTS, HYPOTHETICAL, ABSTRACT)
    }
}

/** How a listening dialogue was written: a classic scripted exchange, or unscripted-sounding speech (BRIEF_V2 §6.10). */
enum class DialogueStyle {
    SCRIPTED,

    /** Fillers, backchannels, restarts and overlaps; fillers are marked so the transcript can grey them. */
    NATURAL,
    ;

    companion object {
        fun parse(value: String?): DialogueStyle = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: SCRIPTED
    }
}

enum class MinimalPairCategory {
    /** Short vs. long vowel: おばさん / おばあさん. */
    LENGTH,
    /** Single vs. double consonant: きて / きって. */
    GEMINATION,
    /** Unvoiced vs. voiced: かき / かぎ. */
    VOICING,
    /** With or without ん: かに / かんに. */
    NASAL,
    /** Same kana, different pitch accent: はし (箸) / はし (橋). */
    PITCH,
}

/** A vocabulary word linked to its JMdict entry in the dictionary pack. */
@Serializable
data class LinkedWord(val text: String, val reading: String, val entryId: Long)

data class Scenario(
    val id: String,
    val titleEn: String,
    val titleJa: String,
    val jlpt: Int,
    /** ILR speaking level the scenario targets: "0+" … "3". */
    val ilr: String,
    val category: String,
    val setting: String,
    val learnerRole: String,
    val partnerRole: String,
    val register: Register,
    val goals: List<String>,
    val vocabulary: List<LinkedWord>,
    val phrases: List<String>,
    /** System prompt for the on-device LLM partner (stay in character, level-adapted, Japanese only). */
    val systemPrompt: String,
    /** "llm" until reviewed; shown with an AI-generated badge (CLAUDE.md rule 10). */
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

/** One partner line of the no-AI fallback conversation, plus what the learner should do in reply. */
data class ScriptedTurn(
    val partnerJa: String,
    val partnerEn: String,
    val intent: String,
    val sampleAnswer: String,
    /** Other acceptable learner replies (varied wording), for a matcher that isn't tied to [sampleAnswer]. */
    val accept: List<String> = emptyList(),
) {
    /** The sample answer first, then the alternatives. */
    val acceptableAnswers: List<String> get() = listOf(sampleAnswer) + accept
}

data class OpiQuestion(
    val phase: OpiPhase,
    val promptJa: String,
    val promptEn: String,
    /** Interviewer's note: what the question elicits or probes for. */
    val note: String,
    val source: String,
    /** DLI-style topic domain; null when untagged. */
    val domain: OpiDomain? = null,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

data class OpiBank(
    val ilr: String,
    val questions: List<OpiQuestion>,
    /** Self-rating statements paraphrased from the public-domain ILR speaking descriptors. */
    val checklist: List<String>,
) {
    fun phase(phase: OpiPhase): List<OpiQuestion> = questions.filter { it.phase == phase }
}

@Serializable
data class Speaker(
    val id: String,
    val name: String,
    /** TTS voice hint: "male" | "female". */
    val voice: String,
    /** TTS voice hint: "young" | "adult" | "senior". */
    val age: String,
    /** Delivery note for the renderer and reviewers ("bright, fast talker"); empty when none. */
    val hint: String = "",
)

/** A gap-fill target inside a line; [start]/[end] are String indices (UTF-16) into [DialogueLine.japanese]. */
@Serializable
data class Gap(val text: String, val start: Int, val end: Int, val entryId: Long)

/** A filler, hesitation or abandoned restart inside a line; [start]/[end] are String indices (UTF-16) into the line. */
@Serializable
data class FillerSpan(val start: Int, val end: Int)

/** A piece of a line for display: fillers are greyed in natural-style transcripts. */
data class LineSegment(val text: String, val isFiller: Boolean)

data class DialogueLine(
    val speaker: String,
    val japanese: String,
    val english: String,
    val gaps: List<Gap>,
    /** Phrase chunks in order; they concatenate to [japanese]. Lines with fewer than 3 don't suit ordering drills. */
    val chunks: List<String>,
    /** Fillers/restarts to grey in the transcript (natural style only), in order, non-overlapping. */
    val fillers: List<FillerSpan> = emptyList(),
    /** The line starts before the previous one ends (a backchannel or an interruption). */
    val overlap: Boolean = false,
) {
    /** [japanese] split into filler and non-filler runs, in order; their texts concatenate to [japanese]. */
    fun segments(): List<LineSegment> {
        val out = mutableListOf<LineSegment>()
        var pos = 0
        for (f in fillers.sortedBy { it.start }) {
            val start = f.start.coerceIn(pos, japanese.length)
            val end = f.end.coerceIn(start, japanese.length)
            if (start > pos) out += LineSegment(japanese.substring(pos, start), false)
            if (end > start) out += LineSegment(japanese.substring(start, end), true)
            pos = end
        }
        if (pos < japanese.length) out += LineSegment(japanese.substring(pos), false)
        return out
    }

    /** The line without its fillers (for dictation or shadowing that skips hesitations). */
    val withoutFillers: String get() = segments().filterNot { it.isFiller }.joinToString("") { it.text }.trim()
}

data class ComprehensionQuestion(val question: String, val choices: List<String>, val answer: Int) {
    val correctChoice: String get() = choices[answer]
}

data class DialogueSummary(
    val id: String,
    val title: String,
    val jlpt: Int,
    val topic: String,
    val source: String,
    val style: DialogueStyle = DialogueStyle.SCRIPTED,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

data class Dialogue(
    val id: String,
    val title: String,
    val jlpt: Int,
    val topic: String,
    val speakers: List<Speaker>,
    val lines: List<DialogueLine>,
    val questions: List<ComprehensionQuestion>,
    val source: String,
    val style: DialogueStyle = DialogueStyle.SCRIPTED,
) {
    val isAiGenerated: Boolean get() = source == "llm"
    fun speaker(id: String): Speaker? = speakers.firstOrNull { it.id == id }
}

/** What a drill set's items come from. */
enum class DrillKind { GRAMMAR, DIALOGUE }

/** One drill: hear [promptEn], answer aloud in the pause, hear [answerJa], repeat it (BRIEF_V2 §6.10, Swotter format). */
data class DrillItem(
    val promptEn: String,
    val answerJa: String,
    /** Pre-rendered clip of the answer (AudioKeys format: grammar/<point>/<ord> or dialogue/<id>/<ord>). */
    val audioKey: String,
    /** "g:<grammar point id>" or "d:<dialogue id>", for "open the source". */
    val ref: String,
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

data class DrillSetSummary(
    val id: String,
    val title: String,
    val jlpt: Int,
    val kind: DrillKind,
    val description: String,
    /** "derived" (human-translated Tatoeba examples), "llm" (any AI-drafted line) or "verified". */
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

data class DrillSet(val summary: DrillSetSummary, val items: List<DrillItem>) {
    val id: String get() = summary.id
}

data class PairWord(
    val entryId: Long,
    val text: String,
    val reading: String,
    /** Kanjium downstep position (0 = heiban), when known. */
    val accent: Int?,
    val gloss: String,
)

/** Two words that differ in one phonological feature, derived from JMdict + Kanjium (no LLM). */
data class MinimalPair(val id: Long, val category: MinimalPairCategory, val a: PairWord, val b: PairWord)
