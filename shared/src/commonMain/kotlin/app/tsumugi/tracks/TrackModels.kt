package app.tsumugi.tracks

import kotlinx.serialization.Serializable

/**
 * An interest or domain track (BRIEF_V2 §6.5): a themed word list + kanji subset + scenarios + dialogues + drills,
 * from the tracks pack (content/packs/tracks.sqlite, built by tools/packs/build_tracks.py). Scenarios and dialogues
 * use the practice models ([app.tsumugi.practice.Scenario], [app.tsumugi.practice.Dialogue]) so the role-play and
 * listening screens show them unchanged.
 */
data class Track(
    val id: String,
    val titleEn: String,
    val titleJa: String,
    val description: String,
    /** The book or course that inspired the structure ("structure and pedagogy only; no content used"). */
    val inspiredBy: String,
    /** Easiest JLPT level of the content (5 = N5). */
    val jlptEasiest: Int,
    /** Hardest JLPT level of the content (1 = N1). */
    val jlptHardest: Int,
    /** ILR range the track targets ("2-3"), or "" when it isn't ILR-leveled. */
    val ilr: String,
    /** Item counts: words, kanji, scenarios, dialogues, drills, drills.<type>, situations, canDo, tasks, readings, links. */
    val counts: Map<String, Int>,
    val license: String,
    val attribution: String,
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
    fun count(key: String): Int = counts[key] ?: 0

    /** Level label for lists: "N5–N2", plus "· ILR 2–3" when leveled. */
    val levelLabel: String
        get() = "N$jlptEasiest–N$jlptHardest" + if (ilr.isNotEmpty()) " · ILR ${ilr.replace("-", "–")}" else ""
}

/** A track in the picker (onboarding, settings) with the learner's selection and progress. */
data class TrackSummary(
    val track: Track,
    val selected: Boolean,
    /** Track words the learner doesn't know and hasn't started yet. */
    val wordsLeft: Int,
)

data class TrackWord(
    val trackId: String,
    val ord: Int,
    val entryId: Long,
    val text: String,
    val reading: String,
    /** English meaning in this track's sense. */
    val gloss: String,
    /** Lesson group ("stream chat"). */
    val topic: String,
    /** "" or yojijukugo | kanyouku | kotowaza | onomatopoeia | synonym | antonym | keigo. */
    val category: String,
    val jlpt: Int?,
    /** Kanjium downstep positions from the dictionary pack (0 = heiban); empty when unknown. */
    val accents: List<Int>,
    val note: String,
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

/** A group of consecutive words from one topic: the unit shown as a "lesson" on the track page. */
data class TrackLesson(val trackId: String, val index: Int, val topic: String, val words: List<TrackWord>)

data class TrackKanji(
    val literal: String,
    val keyword: String,
    val components: List<String>,
    /** Our own component story; empty for derived rows. */
    val breakdown: String,
    /** Our own memory hint; empty for derived rows. */
    val hint: String,
    /** JMdict ids of this track's words that use the kanji. */
    val wordIds: List<Long>,
    /** "llm" (hint drafted, badge on) | "derived" (from KANJIDIC2/KRADFILE) | "verified". */
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

@Serializable
data class CanDo(val en: String, val ja: String)

/** A daily-life situation with can-do statements; [canDoId] keys the learner's self-check marks. */
data class TrackSituation(val id: String, val trackId: String, val titleEn: String, val titleJa: String, val canDo: List<CanDo>, val source: String) {
    val isAiGenerated: Boolean get() = source == "llm"
    fun canDoId(index: Int): String = "$id/$index"
}

/** A cultural experience task (festival, onsen, tea …): prepare, do, reflect. */
data class CultureTask(
    val id: String,
    val trackId: String,
    val titleEn: String,
    val titleJa: String,
    val place: String,
    val before: List<String>,
    val during: List<String>,
    val after: List<String>,
    val phrases: List<String>,
    val etiquette: List<String>,
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

@Serializable
data class ReadingQuestion(val question: String, val choices: List<String>, val answer: Int)

/** An ILR-leveled reading passage (fictional briefings, notices, editorials). */
data class TrackReading(
    val id: String,
    val trackId: String,
    val title: String,
    val ilr: String,
    val genre: String,
    val body: String,
    val questions: List<ReadingQuestion>,
    val source: String,
) {
    val isAiGenerated: Boolean get() = source == "llm"
}

/** A link-only reference (official texts we never copy, e.g. JMSDF/JASDF press releases). */
data class TrackLink(val title: String, val url: String, val note: String)
