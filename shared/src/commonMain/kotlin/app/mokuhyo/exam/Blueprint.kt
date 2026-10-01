package app.mokuhyo.exam

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reading or Listening (BRIEF §2). */
enum class Skill(val id: String, val exam: ExamKind) {
    READING("reading", ExamKind.DLPT_READING),
    LISTENING("listening", ExamKind.DLPT_LISTENING),
    ;

    companion object {
        fun of(id: String): Skill = entries.first { it.id == id }
        fun of(exam: ExamKind): Skill = entries.first { it.exam == exam }
    }
}

/** Test lengths (BRIEF §2): full length, or 60/30-minute slices. */
enum class FormLength(val minutes: Int, val mode: ExamMode, val title: String) {
    FULL(180, ExamMode.FULL, "Full length (3 hours)"),
    SLICE_60(60, ExamMode.SLICE_60, "60-minute slice"),
    SLICE_30(30, ExamMode.SLICE_30, "30-minute slice"),
}

/**
 * Per-language exam blueprint (BRIEF §5.1), shipped as data in the language pack (`exam.json` → `blueprint`).
 * Item counts are for a full-length form; slices scale them by minutes, at least one item per level.
 */
@Serializable
data class ExamBlueprint(
    val language: String,
    val version: Int = 1,
    val reading: SectionBlueprint,
    val listening: SectionBlueprint,
    /** Questions and answer choices are in English (lower-range DLPT style). */
    val englishQuestions: Boolean = true,
    /** A test form never reuses a passage the learner saw in their last [noRepeatForms] test forms of that skill. */
    val noRepeatForms: Int = 3,
    val notes: String = "",
) {
    fun section(skill: Skill): SectionBlueprint = if (skill == Skill.READING) reading else listening

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): ExamBlueprint = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class SectionBlueprint(
    /** ILR levels covered, easiest first, e.g. ["0+","1","1+","2","2+","3"]. */
    val levels: List<String>,
    /** Items per level on a full-length form. */
    val itemsPerLevel: Map<String, Int>,
    val fullMinutes: Int = 180,
    /** Listening only: how often a passage may be played on a test, and whether questions show before it plays. */
    val play: PlayPolicy = PlayPolicy(),
    /** Text types per level (the mix the drafting and validation tools follow). */
    val textTypes: Map<String, List<String>> = emptyMap(),
    val questionTypes: List<String> = listOf("main_idea", "detail", "inference", "purpose", "vocabulary_in_context", "tone"),
) {
    val fullItems: Int get() = levels.sumOf { itemsPerLevel[it] ?: 0 }

    /** Items per level for a form of [length]: full counts scaled by minutes, at least 1 per level. */
    fun itemsFor(length: FormLength): Map<String, Int> {
        if (length == FormLength.FULL) return levels.associateWith { itemsPerLevel[it] ?: 0 }
        val factor = length.minutes.toDouble() / fullMinutes
        return levels.associateWith { lv -> maxOf(1, kotlin.math.round((itemsPerLevel[lv] ?: 0) * factor).toInt()) }
    }

    fun minutesFor(length: FormLength): Int = if (length == FormLength.FULL) fullMinutes else length.minutes
}

@Serializable
data class PlayPolicy(val plays: Int = 1, val questionsVisibleBeforeAudio: Boolean = false)
