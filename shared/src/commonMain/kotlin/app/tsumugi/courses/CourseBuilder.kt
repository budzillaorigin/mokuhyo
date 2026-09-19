package app.tsumugi.courses

import app.tsumugi.exam.ExamMode
import kotlin.math.ceil
import kotlin.math.max

/**
 * Builds a JLPT course from data the app already has (BRIEF_V2 §6.6, DECISIONS D-230). It's pure: [CourseService]
 * gathers the inputs.
 *
 * - **Modules.** A level has one module per [GRAMMAR_PER_MODULE] grammar points (at least one). Its kanji and words,
 *   in path order, are split evenly across the modules, so module n teaches the kanji, then the words that use them,
 *   then its grammar.
 * - **Quiz.** Each module quizzes one kanji type, one vocabulary type and one grammar type from the level's exam bank,
 *   rotating through the types the bank has.
 * - **Mock.** Each module ends with one blueprint section as a timed mock, cycling through the level's sections.
 * - **Progress.** Kanji and words count when learned (Guru+ or marked known). Grammar counts only when its mastery
 *   checkbox is ticked. Sections count once passed.
 */
object CourseBuilder {
    const val GRAMMAR_PER_MODULE = 8
    /** Kanji + words per module when the level has no grammar points (grammar pack missing). */
    const val WORDS_PER_MODULE_WITHOUT_GRAMMAR = 60
    const val QUIZ_PASS = 0.8
    const val QUIZ_MIN_ITEMS = 5
    /** A section attempt counts only when it answered at least this share of the section's blueprint items. */
    const val SECTION_MIN_SHARE = 0.5
    /** Total scaled points of every JLPT level; the pass mark is out of this. */
    const val JLPT_TOTAL = 180

    val KANJI_TYPES = listOf("kanji_reading", "orthography")
    val VOCAB_TYPES = listOf("context", "paraphrase", "usage", "word_formation")
    val GRAMMAR_TYPES = listOf("grammar_form", "sentence_assembly", "text_grammar")

    fun build(level: Int, input: CourseInput): JlptCourse {
        val count = moduleCount(input)
        val kanji = input.kanji.sortedBy { it.pathLevel } // stable: pack order within a path level
        val words = input.words.sortedBy { it.pathLevel }
        val sections = input.sections.map { sectionResult(it, input.passMark, input.attempts) }
        val quizTypes = listOf(KANJI_TYPES, VOCAB_TYPES, GRAMMAR_TYPES).map { group -> group.filter { (input.bankTypes[it] ?: 0) > 0 } }
        val modules = (0 until count).map { i ->
            CourseModule(
                index = i + 1,
                kanji = kanji.slice(chunk(kanji.size, count, i)),
                words = words.slice(chunk(words.size, count, i)),
                grammar = input.grammar.slice(chunk(input.grammar.size, count, i)),
                quiz = quizTypes.mapNotNull { types -> types.takeIf { it.isNotEmpty() }?.let { it[i % it.size] } }
                    .map { quizType(it, input) },
                mock = sections.takeIf { it.isNotEmpty() }?.let { it[i % it.size] },
            )
        }
        return JlptCourse(level, if (input.isEmpty()) emptyList() else modules, progress(input, sections), sections)
    }

    /** What remains for the level, in course order: unlearned kanji and words, unmastered grammar, unpassed sections. */
    fun remaining(course: JlptCourse): LevelRemaining = LevelRemaining(
        level = course.level,
        kanji = course.modules.flatMap { m -> m.kanji.filter { !it.learned } },
        words = course.modules.flatMap { m -> m.words.filter { !it.learned } },
        grammar = course.modules.flatMap { m -> m.grammar.filter { !it.mastered } },
        mockSections = course.sections.filter { !it.passed },
    )

    fun progress(input: CourseInput): CourseProgress =
        progress(input, input.sections.map { sectionResult(it, input.passMark, input.attempts) })

    private fun progress(input: CourseInput, sections: List<CourseMockSection>) = CourseProgress(
        kanjiLearned = input.kanji.count { it.learned }, kanjiTotal = input.kanji.size,
        wordsLearned = input.words.count { it.learned }, wordsTotal = input.words.size,
        grammarMastered = input.grammar.count { it.mastered }, grammarTotal = input.grammar.size,
        sectionsPassed = sections.count { it.passed }, sectionsTotal = sections.size,
    )

    fun moduleCount(input: CourseInput): Int = when {
        input.grammar.isNotEmpty() -> ceil(input.grammar.size / GRAMMAR_PER_MODULE.toDouble()).toInt()
        else -> max(1, ceil((input.kanji.size + input.words.size) / WORDS_PER_MODULE_WITHOUT_GRAMMAR.toDouble()).toInt())
    }

    /** Index range of part [i] of [count] near-equal consecutive parts of [size] items. */
    internal fun chunk(size: Int, count: Int, i: Int): IntRange = (size * i / count) until (size * (i + 1) / count)

    /**
     * A section's best result. Mock and section attempts at the level count when they answered at least
     * [SECTION_MIN_SHARE] of the section's items. The section is passed when the accuracy over its item types reaches the
     * level's pass rate (pass mark / 180; 50% when unknown).
     */
    fun sectionResult(spec: CourseSectionSpec, passMark: Int?, attempts: List<CourseAttempt>): CourseMockSection {
        val needed = ceil(spec.itemCount * SECTION_MIN_SHARE).toInt().coerceAtLeast(1)
        val passRate = (passMark ?: (JLPT_TOTAL / 2)).toDouble() / JLPT_TOTAL
        val best = attempts.filter { it.mode == ExamMode.MOCK || it.mode == ExamMode.SECTION }.mapNotNull { a ->
            val tallies = spec.types.keys.mapNotNull { a.byType[it] }
            val total = tallies.sumOf { it.second }
            if (total < needed) null else tallies.sumOf { it.first }.toDouble() / total
        }.maxOrNull()
        return CourseMockSection(spec.id, spec.title, spec.minutes, spec.itemCount, best, best != null && best >= passRate)
    }

    private fun quizType(type: String, input: CourseInput): CourseQuizType {
        val best = input.attempts.mapNotNull { a ->
            a.byType[type]?.takeIf { it.second >= QUIZ_MIN_ITEMS }?.let { it.first.toDouble() / it.second }
        }.maxOrNull()
        return CourseQuizType(type, input.typeTitles[type] ?: type, input.bankTypes[type] ?: 0, best, best != null && best >= QUIZ_PASS)
    }

    private fun CourseInput.isEmpty() = kanji.isEmpty() && words.isEmpty() && grammar.isEmpty()
}
