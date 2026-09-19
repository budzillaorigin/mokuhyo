package app.tsumugi.courses

import app.tsumugi.exam.ExamMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BRIEF_V2 §6.6 course view (DECISIONS D-230): modules, quizzes, mock sections, progress, "one book to pass". */
class CourseBuilderTest {
    private fun kanji(n: Int, learned: Int = 0) = (1..n).map { CourseKanji("k:$it", "字$it", "kw$it", pathLevel = (it + 9) / 10, learned = it <= learned) }
    private fun words(n: Int, learned: Int = 0) = (1..n).map { CourseWord("v:$it", "語$it", "ご", "word $it", it.toLong(), (it + 19) / 20, it <= learned) }
    private fun grammar(n: Int, mastered: Int = 0) = (1..n).map { CourseGrammar("g$it", "〜$it", "m$it", it <= mastered, null, true) }

    private val sections = listOf(
        CourseSectionSpec("vocabulary", "言語知識（文字・語彙）", 30, mapOf("kanji_reading" to 8, "orthography" to 6, "context" to 11)),
        CourseSectionSpec("grammar_reading", "言語知識（文法）・読解", 70, mapOf("grammar_form" to 13, "sentence_assembly" to 5)),
        CourseSectionSpec("listening", "聴解", 40, mapOf("task" to 6, "point" to 6)),
    )

    private fun input(
        k: List<CourseKanji> = kanji(40),
        w: List<CourseWord> = words(100),
        g: List<CourseGrammar> = grammar(20),
        attempts: List<CourseAttempt> = emptyList(),
        bank: Map<String, Int> = mapOf("kanji_reading" to 80, "orthography" to 80, "context" to 80, "paraphrase" to 8, "grammar_form" to 80),
    ) = CourseInput(k, w, g, sections, passMark = 95, bankTypes = bank, typeTitles = mapOf("kanji_reading" to "漢字読み"), attempts = attempts)

    @Test
    fun modulesSplitTheLevelInOrderAndCoverEverything() {
        val course = CourseBuilder.build(3, input())
        assertEquals(3, course.modules.size) // 20 grammar points / 8 per module, rounded up
        assertEquals(listOf(6, 7, 7), course.modules.map { it.grammar.size }) // split evenly, in order
        assertEquals(40, course.modules.sumOf { it.kanji.size })
        assertEquals(100, course.modules.sumOf { it.words.size })
        assertEquals(20, course.modules.sumOf { it.grammar.size })
        // Path order: module 1 holds the earliest kanji.
        assertEquals("k:1", course.modules.first().kanji.first().itemId)
        assertEquals("k:40", course.modules.last().kanji.last().itemId)
        // Step order is kanji → vocab → grammar → quiz → mock.
        assertEquals(CourseStepKind.entries.toList(), course.modules.first().steps.map { it.kind })
        assertEquals(CourseStepKind.KANJI, course.modules.first().nextStep)
        assertEquals(1, course.currentModule?.index)
    }

    @Test
    fun quizzesRotateThroughTheBankTypesAndMocksCycleSections() {
        val course = CourseBuilder.build(3, input())
        // Kanji types in the bank: kanji_reading, orthography → alternate. Vocab: context, paraphrase. Grammar: grammar_form only.
        assertEquals(listOf("kanji_reading", "context", "grammar_form"), course.modules[0].quiz.map { it.type })
        assertEquals(listOf("orthography", "paraphrase", "grammar_form"), course.modules[1].quiz.map { it.type })
        assertEquals("漢字読み", course.modules[0].quiz[0].title)
        assertEquals(listOf("vocabulary", "grammar_reading", "listening"), course.modules.map { it.mock?.sectionId })
    }

    @Test
    fun typesMissingFromTheBankAreLeftOut() {
        val course = CourseBuilder.build(5, input(bank = mapOf("grammar_form" to 10)))
        assertTrue(course.modules.all { m -> m.quiz.map { it.type } == listOf("grammar_form") })
    }

    @Test
    fun progressIsTheMeanOfTheCategoriesAndGrammarCountsOnlyMastery() {
        val p = CourseBuilder.build(3, input(k = kanji(40, learned = 20), w = words(100, learned = 100), g = grammar(20, mastered = 5))).progress
        assertEquals(20, p.kanjiLearned)
        assertEquals(5, p.grammarMastered)
        assertEquals(0, p.sectionsPassed)
        // (0.5 + 1.0 + 0.25 + 0.0) / 4
        assertEquals(0.4375, p.fraction, 1e-9)
        assertEquals(43, p.percent)
    }

    @Test
    fun sectionPassNeedsEnoughItemsAtThePassRate() {
        val spec = sections[1] // 18 items; pass rate 95/180 ≈ 0.53
        fun attempt(mode: ExamMode, correct: Int, total: Int) = CourseAttempt(mode, mapOf("grammar_form" to (correct to total)))
        assertFalse(CourseBuilder.sectionResult(spec, 95, listOf(attempt(ExamMode.TYPE, 10, 10))).passed, "type drills don't count")
        assertFalse(CourseBuilder.sectionResult(spec, 95, listOf(attempt(ExamMode.SECTION, 8, 8))).passed, "too few items (< half of 18)")
        assertFalse(CourseBuilder.sectionResult(spec, 95, listOf(attempt(ExamMode.SECTION, 9, 18))).passed, "0.50 < 0.53")
        val passed = CourseBuilder.sectionResult(spec, 95, listOf(attempt(ExamMode.SECTION, 9, 18), attempt(ExamMode.MOCK, 12, 18)))
        assertTrue(passed.passed)
        assertEquals(12.0 / 18, passed.bestAccuracy!!, 1e-9)
        assertNull(CourseBuilder.sectionResult(spec, 95, emptyList()).bestAccuracy)
    }

    @Test
    fun quizPassesAtEightyPercentOverFiveItems() {
        val course = CourseBuilder.build(3, input(attempts = listOf(CourseAttempt(ExamMode.TYPE, mapOf("kanji_reading" to (8 to 10), "context" to (4 to 4))))))
        val quiz = course.modules[0].quiz
        assertTrue(quiz.first { it.type == "kanji_reading" }.passed)
        assertFalse(quiz.first { it.type == "context" }.passed, "4 items are too few to count")
    }

    @Test
    fun oneBookToPassListsExactlyWhatRemains() {
        val attempts = listOf(CourseAttempt(ExamMode.SECTION, mapOf("task" to (6 to 6), "point" to (5 to 6))))
        val course = CourseBuilder.build(3, input(k = kanji(10, learned = 7), w = words(5, learned = 5), g = grammar(9, mastered = 8), attempts = attempts))
        val left = CourseBuilder.remaining(course)
        assertEquals(listOf("k:8", "k:9", "k:10"), left.kanji.map { it.itemId })
        assertTrue(left.words.isEmpty())
        assertEquals(listOf("g9"), left.grammar.map { it.pointId })
        assertEquals(listOf("vocabulary", "grammar_reading"), left.mockSections.map { it.sectionId }, "listening was passed")
        assertEquals(3 + 1 + 2, left.total)
        assertFalse(left.isDone)
    }

    @Test
    fun aLevelWithoutGrammarStillHasModulesAndAnEmptyLevelHasNone() {
        val noGrammar = CourseBuilder.build(1, input(k = kanji(100), w = words(80), g = emptyList()))
        assertEquals(3, noGrammar.modules.size) // 180 items / 60
        val empty = CourseBuilder.build(1, input(k = emptyList(), w = emptyList(), g = emptyList()))
        assertTrue(empty.modules.isEmpty())
        assertNull(empty.currentModule)
        assertEquals(0.0, empty.progress.fraction) // only the sections count, none passed
    }

    @Test
    fun chunksAreContiguousAndComplete() {
        for (size in 0..30) for (count in 1..7) {
            val parts = (0 until count).map { CourseBuilder.chunk(size, count, it) }
            assertEquals((0 until size).toList(), parts.flatMap { it.toList() }, "size $size count $count")
        }
    }
}
