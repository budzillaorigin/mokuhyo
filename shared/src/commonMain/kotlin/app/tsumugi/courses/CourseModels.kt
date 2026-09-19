package app.tsumugi.courses

import app.tsumugi.domain.Stage
import app.tsumugi.exam.ExamMode

/** The five steps of a course module, in the order they are taken (BRIEF_V2 §6.6, Yomimono's structure). */
enum class CourseStepKind { KANJI, VOCAB, GRAMMAR, QUIZ, MOCK }

/** A kanji of the level (a kanji-path item with this JLPT tag). [learned] = Guru or above, or marked known. */
data class CourseKanji(
    val itemId: String,
    val text: String,
    val keyword: String,
    val pathLevel: Int,
    val learned: Boolean,
)

/** A word of the level (a kanji-path vocab item with this JLPT tag). [learned] = Guru or above, or marked known. */
data class CourseWord(
    val itemId: String,
    val text: String,
    val reading: String,
    val meaning: String,
    val entryId: Long?,
    val pathLevel: Int,
    val learned: Boolean,
)

/**
 * A grammar point of the level. [mastered] is the learner's own checkbox, independent of SRS ([stage]); only the
 * checkbox counts toward the course (D-231). [aiGenerated]: the explanation still carries the badge.
 */
data class CourseGrammar(
    val pointId: String,
    val title: String,
    val meaning: String,
    val mastered: Boolean,
    val stage: Stage?,
    val aiGenerated: Boolean,
)

/**
 * One item type drilled in a module quiz (an exam-bank item type, run with `ExamService.jlptTypeDrill`).
 * [bankItems]: items the bank has for it. [passed]: some attempt at this level answered at least
 * [CourseBuilder.QUIZ_MIN_ITEMS] items of the type with [CourseBuilder.QUIZ_PASS] accuracy or better.
 */
data class CourseQuizType(
    val type: String,
    val title: String,
    val bankItems: Int,
    val bestAccuracy: Double?,
    val passed: Boolean,
)

/** A blueprint section taken as a timed mock (`ExamService.jlptSection`). See [CourseBuilder.sectionResult]. */
data class CourseMockSection(
    val sectionId: String,
    val title: String,
    val minutes: Int,
    val itemCount: Int,
    val bestAccuracy: Double?,
    val passed: Boolean,
)

/** Progress of one step of a module: [done] of [total] (items learned, points mastered, types or sections passed). */
data class CourseStep(val kind: CourseStepKind, val done: Int, val total: Int) {
    val complete: Boolean get() = done >= total
    val fraction: Double get() = if (total == 0) 1.0 else done.toDouble() / total
}

/** One module: kanji → vocab → grammar → quiz → mock section. [index] is 1-based. */
data class CourseModule(
    val index: Int,
    val kanji: List<CourseKanji>,
    val words: List<CourseWord>,
    val grammar: List<CourseGrammar>,
    val quiz: List<CourseQuizType>,
    val mock: CourseMockSection?,
) {
    val steps: List<CourseStep>
        get() = listOfNotNull(
            CourseStep(CourseStepKind.KANJI, kanji.count { it.learned }, kanji.size).takeIf { it.total > 0 },
            CourseStep(CourseStepKind.VOCAB, words.count { it.learned }, words.size).takeIf { it.total > 0 },
            CourseStep(CourseStepKind.GRAMMAR, grammar.count { it.mastered }, grammar.size).takeIf { it.total > 0 },
            CourseStep(CourseStepKind.QUIZ, quiz.count { it.passed }, quiz.size).takeIf { it.total > 0 },
            mock?.let { CourseStep(CourseStepKind.MOCK, if (it.passed) 1 else 0, 1) },
        )

    val complete: Boolean get() = steps.all { it.complete }

    /** The first step that isn't complete, or null when the module is done. */
    val nextStep: CourseStepKind? get() = steps.firstOrNull { !it.complete }?.kind
}

/** Per-category counts for a level; [fraction] is the mean of the categories the level has content for. */
data class CourseProgress(
    val kanjiLearned: Int,
    val kanjiTotal: Int,
    val wordsLearned: Int,
    val wordsTotal: Int,
    val grammarMastered: Int,
    val grammarTotal: Int,
    val sectionsPassed: Int,
    val sectionsTotal: Int,
) {
    val fraction: Double
        get() {
            val parts = listOf(kanjiLearned to kanjiTotal, wordsLearned to wordsTotal, grammarMastered to grammarTotal, sectionsPassed to sectionsTotal)
                .filter { it.second > 0 }
            return if (parts.isEmpty()) 0.0 else parts.sumOf { it.first.toDouble() / it.second } / parts.size
        }

    /** Whole percent for a progress bar label. */
    val percent: Int get() = (fraction * 100).toInt()

    val isEmpty: Boolean get() = kanjiTotal + wordsTotal + grammarTotal + sectionsTotal == 0
}

/** A structured JLPT course for one level (BRIEF_V2 §6.6). */
data class JlptCourse(
    val level: Int,
    val modules: List<CourseModule>,
    val progress: CourseProgress,
    /** Every blueprint section of the level with its best result (modules each take one of these in turn). */
    val sections: List<CourseMockSection>,
) {
    /** The first module with something left, or null when the level is done (or empty). */
    val currentModule: CourseModule? get() = modules.firstOrNull { !it.complete }
}

/** "One book to pass" (日本語の森's promise): exactly what remains for the level, in course order. */
data class LevelRemaining(
    val level: Int,
    val kanji: List<CourseKanji>,
    val words: List<CourseWord>,
    val grammar: List<CourseGrammar>,
    val mockSections: List<CourseMockSection>,
) {
    val total: Int get() = kanji.size + words.size + grammar.size + mockSections.size
    val isDone: Boolean get() = total == 0
}

/** One level's bar on the course overview. */
data class LevelProgress(val level: Int, val progress: CourseProgress)

/** A JLPT attempt as the course sees it: its mode and per-item-type tallies (correct to total). */
data class CourseAttempt(val mode: ExamMode, val byType: Map<String, Pair<Int, Int>>)

/** Everything [CourseBuilder.build] needs, gathered by [CourseService] (or built by hand in tests). */
data class CourseInput(
    val kanji: List<CourseKanji>,
    val words: List<CourseWord>,
    val grammar: List<CourseGrammar>,
    /** Blueprint sections of the level: id, title, minutes, and the item types with their counts. */
    val sections: List<CourseSectionSpec>,
    /** Pass mark of the level over the 180-point total (e.g. 95 for N3); null when unknown. */
    val passMark: Int?,
    /** Exam-bank items per item type at this level. */
    val bankTypes: Map<String, Int>,
    /** Item-type titles from the blueprint (kanji_reading → 漢字読み). */
    val typeTitles: Map<String, String>,
    /** JLPT attempts at this level. */
    val attempts: List<CourseAttempt>,
)

data class CourseSectionSpec(val id: String, val title: String, val minutes: Int, val types: Map<String, Int>) {
    val itemCount: Int get() = types.values.sum()
}
