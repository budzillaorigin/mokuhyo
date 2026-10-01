package app.mokuhyo.history

import app.mokuhyo.db.DatabaseFactory
import app.mokuhyo.exam.ExamAssembler
import app.mokuhyo.exam.ExamBlueprint
import app.mokuhyo.exam.ExamItem
import app.mokuhyo.exam.ExamPassage
import app.mokuhyo.exam.ExamSession
import app.mokuhyo.exam.FormLength
import app.mokuhyo.exam.SectionBlueprint
import app.mokuhyo.exam.Skill
import app.mokuhyo.srs.Rating
import app.mokuhyo.srs.ReviewService
import app.mokuhyo.testing.TestClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

class HistoryAndReviewTest {
    private val levels = listOf("0+", "1", "1+", "2", "2+", "3")
    private val blueprint = SectionBlueprint(levels, levels.associateWith { 6 }).let { ExamBlueprint("es", reading = it, listening = it) }
    private val passages = levels.flatMap { l -> (1..6).map { ExamPassage("$l-$it", Skill.READING.exam, "es", l, "news", "t", "b", emptyList(), "llm", false) } }.associateBy { it.id }
    private val items = passages.values.flatMap { p -> (1..3).map { ExamItem("${p.id}-$it", "b", p.exam, p.level, "detail", p.id, "s", listOf("a", "b", "c", "d"), 0, "", emptyList(), emptyList(), "llm", false) } }

    private fun take(length: FormLength, clock: TestClock, recent: Set<String> = emptySet()) =
        ExamSession(ExamAssembler.test(blueprint, Skill.READING, length, items, passages, recent, Random(1)), clock = clock).let { s ->
            s.form.items.forEachIndexed { i, f -> s.goTo(i); s.choose(f.item.answer) }
            s.submit()
        }

    @Test
    fun testsRecordEstimatesPracticeAndLocalBankDoNot() {
        val (_, db) = DatabaseFactory.inMemory()
        val clock = TestClock()
        val history = HistoryRepository(db, clock)
        history.saveAttempt("L", Skill.READING, take(FormLength.FULL, clock))
        val practice = ExamSession(ExamAssembler.practice("es", Skill.READING, "2", null, items, passages, emptySet(), Random(2)), clock = clock).submit()
        history.saveAttempt("L", Skill.READING, practice)
        history.saveAttempt("L", Skill.READING, take(FormLength.SLICE_30, clock), bank = "local")
        assertEquals(3, history.attempts("L", "es").size)
        val estimates = history.estimates("L", "es")
        assertEquals(1, estimates.size)
        assertEquals("READING", estimates.single().modality)
        assertEquals("3", estimates.single().value)
        assertNotNull(history.latest("L", "es")["READING"])
    }

    @Test
    fun recentTestPassagesFeedTheNoRepeatRule() {
        val (_, db) = DatabaseFactory.inMemory()
        val clock = TestClock()
        val history = HistoryRepository(db, clock)
        val first = take(FormLength.SLICE_60, clock)
        history.saveAttempt("L", Skill.READING, first)
        val recent = history.recentTestPassages("L", "es", Skill.READING, 3)
        assertEquals(first.form.passageIds.toSet(), recent)
        val second = take(FormLength.SLICE_60, clock, recent)
        assertTrue(second.form.passageIds.none { it in recent })
    }

    @Test
    fun storedFormKeepsTheShownChoiceOrder() {
        val (_, db) = DatabaseFactory.inMemory()
        val clock = TestClock()
        val history = HistoryRepository(db, clock)
        val result = take(FormLength.SLICE_30, clock)
        val id = history.saveAttempt("L", Skill.READING, result)
        val stored = history.form(history.attempts("L", "es").first { it.id == id })
        assertEquals(result.form.items.map { it.item.choices }, stored.items.map { it.choices })
        assertEquals(result.form.items.map { it.item.answer }, stored.items.map { it.answer })
    }

    @Test
    fun tombstonesHideButNeverDelete() {
        val (driver, db) = DatabaseFactory.inMemory()
        val clock = TestClock()
        val history = HistoryRepository(db, clock)
        val id = history.saveAttempt("L", Skill.READING, take(FormLength.SLICE_30, clock))
        history.tombstoneAttempt(id)
        assertTrue(history.attempts("L", "es").isEmpty())
        assertEquals(1, db.historyQueries.attemptsAll().executeAsList().size)
        driver.close()
    }

    @Test
    fun reviewQueueSchedulesAndUndoIsATombstone() {
        val (_, db) = DatabaseFactory.inMemory()
        val clock = TestClock()
        val reviews = ReviewService(db, clock = clock)
        val id = reviews.add("L", "es", ReviewService.Kind.WORD, "dict:123", "biblioteca", "library", "en la biblioteca municipal")
        assertEquals(id, reviews.add("L", "es", ReviewService.Kind.WORD, "dict:123", "biblioteca", "library"))
        assertEquals(1L, reviews.dueCount("L", "es"))
        val before = reviews.card(id)!!
        val r1 = reviews.grade(id, Rating.GOOD)
        reviews.grade(id, Rating.GOOD)
        clock.advance(1.days)
        assertTrue(reviews.card(id)!!.reps == 2)
        reviews.undo(r1, id)
        assertEquals(1, reviews.card(id)!!.reps)
        assertEquals(2, db.srsQueries.reviewsAll().executeAsList().size) // the undone review is still there, tombstoned
        assertTrue(before.reps == 0)
        assertEquals(0L, reviews.dueCount("L", "fr"))
    }
}
