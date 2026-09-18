package app.tsumugi.practice

import kotlinx.serialization.Serializable

/** Politeness level a role-play is conducted in. */
enum class Register { CASUAL, POLITE, KEIGO }

/** Structure of an OPI-style interview (BRIEF §5.10). */
enum class OpiPhase { WARM_UP, LEVEL_CHECK, PROBE, ROLE_PLAY, WIND_DOWN }

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
)

data class OpiQuestion(
    val phase: OpiPhase,
    val promptJa: String,
    val promptEn: String,
    /** Interviewer's note: what the question elicits or probes for. */
    val note: String,
    val source: String,
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
)

/** A gap-fill target inside a line; [start]/[end] are String indices (UTF-16) into [DialogueLine.japanese]. */
@Serializable
data class Gap(val text: String, val start: Int, val end: Int, val entryId: Long)

data class DialogueLine(
    val speaker: String,
    val japanese: String,
    val english: String,
    val gaps: List<Gap>,
    /** Phrase chunks in order; they concatenate to [japanese]. Lines with fewer than 3 don't suit ordering drills. */
    val chunks: List<String>,
)

data class ComprehensionQuestion(val question: String, val choices: List<String>, val answer: Int) {
    val correctChoice: String get() = choices[answer]
}

data class DialogueSummary(val id: String, val title: String, val jlpt: Int, val topic: String, val source: String) {
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
) {
    val isAiGenerated: Boolean get() = source == "llm"
    fun speaker(id: String): Speaker? = speakers.firstOrNull { it.id == id }
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
