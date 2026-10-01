package app.mokuhyo.exam

import app.mokuhyo.testing.TestClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** BRIEF §5.1 / CLAUDE.md rule 10: exam assembly and scoring tests (fixtures; no packs needed). */
class ExamSessionTest {
    private val levels = listOf("0+", "1", "1+", "2", "2+", "3")

    private val section = SectionBlueprint(levels, levels.associateWith { 10 }, fullMinutes = 180)
    private val blueprint = ExamBlueprint("xx", reading = section, listening = section.copy(play = PlayPolicy(plays = 1)))

    /** [perLevel] passages per level with 3 items each; answers all at index 0 (the assembler must spread them). */
    private fun pool(skill: Skill = Skill.READING, perLevel: Int = 8, verified: Set<String> = emptySet()): Pair<List<ExamItem>, Map<String, ExamPassage>> {
        val passages = levels.flatMap { l -> (1..perLevel).map { n -> passage("$l-$n", l, skill) } }.associateBy { it.id }
        val items = passages.values.flatMap { p ->
            (1..3).map { q ->
                ExamItem("${p.id}-q$q", "test", skill.exam, p.level, "detail", p.id, "stem ${p.id} $q", listOf("right", "w1", "w2", "w3"), 0, "", emptyList(), emptyList(), "llm", p.id in verified)
            }
        }
        return items to passages
    }

    private fun passage(id: String, level: String, skill: Skill) =
        ExamPassage(id, skill.exam, "xx", level, "news", "T $id", "body", emptyList(), "llm", false)

    @Test
    fun fullFormFollowsTheBlueprintEasiestFirstWithWholePassages() {
        val (items, passages) = pool()
        val form = ExamAssembler.test(blueprint, Skill.READING, FormLength.FULL, items, passages, emptySet(), Random(1))
        assertEquals(ExamMode.FULL, form.mode)
        assertEquals(180, form.totalMinutes)
        // 10 items per level wanted, passages come in 3s → 9 per level (whole groups only; 4th would overshoot).
        levels.forEach { l -> assertEquals(9, form.items.count { it.item.level == l }, "level $l") }
        assertEquals(levels, form.items.map { it.item.level }.distinct())
        form.items.groupBy { it.item.passageId }.forEach { (_, g) -> assertEquals(3, g.size) }
        assertTrue(form.shortfalls.all { it.got == 9 })
    }

    @Test
    fun slicesScaleItemCounts() {
        assertEquals(levels.associateWith { 3 }, section.itemsFor(FormLength.SLICE_60))
        assertEquals(levels.associateWith { 2 }, section.itemsFor(FormLength.SLICE_30))
        val (items, passages) = pool()
        val form = ExamAssembler.test(blueprint, Skill.READING, FormLength.SLICE_30, items, passages, emptySet(), Random(2))
        assertEquals(ExamMode.SLICE_30, form.mode)
        assertEquals(30, form.totalMinutes)
        // 2 wanted per level, groups of 3: the first group is taken even though it overshoots.
        levels.forEach { l -> assertEquals(3, form.items.count { it.item.level == l }) }
    }

    @Test
    fun recentlySeenPassagesAreNeverReused() {
        val (items, passages) = pool(perLevel = 4)
        val first = ExamAssembler.test(blueprint, Skill.READING, FormLength.SLICE_60, items, passages, emptySet(), Random(3))
        val second = ExamAssembler.test(blueprint, Skill.READING, FormLength.SLICE_60, items, passages, first.passageIds.toSet(), Random(4))
        assertTrue(first.passageIds.intersect(second.passageIds.toSet()).isEmpty())
        // Exhaust the pool: nothing new left → empty levels are reported as shortfalls, never refilled with repeats.
        val seen = items.mapNotNull { it.passageId }.toSet()
        val third = ExamAssembler.test(blueprint, Skill.READING, FormLength.SLICE_60, items, passages, seen, Random(5))
        assertTrue(third.isEmpty)
        assertEquals(levels.size, third.shortfalls.size)
    }

    @Test
    fun answerKeysAreBalanced() {
        val (items, passages) = pool()
        val form = ExamAssembler.test(blueprint, Skill.READING, FormLength.FULL, items, passages, emptySet(), Random(6))
        val counts = form.items.groupingBy { it.item.answer }.eachCount()
        assertEquals(setOf(0, 1, 2, 3), counts.keys)
        assertTrue(counts.values.max() - counts.values.min() <= 1, "unbalanced keys $counts")
        form.items.forEach { assertEquals("right", it.item.choices[it.item.answer]) }
    }

    @Test
    fun verifiedPassagesArePreferred() {
        val (items, passages) = pool(verified = setOf("2-7", "2-8"))
        repeat(5) { seed ->
            val form = ExamAssembler.test(blueprint, Skill.READING, FormLength.SLICE_60, items, passages, emptySet(), Random(seed))
            assertEquals(setOf("2-7", "2-8").take(1).size, 1)
            assertTrue(form.items.filter { it.item.level == "2" }.all { it.item.verified }, "seed $seed")
        }
    }

    @Test
    fun strictSectionsCloseWhenTimeRunsOut() {
        val (items, passages) = pool()
        val clock = TestClock()
        val session = ExamSession(ExamAssembler.test(blueprint, Skill.READING, FormLength.SLICE_30, items, passages, emptySet(), Random(1)), clock = clock)
        assertEquals(30 * 60_000L, session.remainingMs())
        clock.advance(29.minutes)
        assertFalse(session.tick())
        clock.advance(2.minutes)
        assertTrue(session.tick())
        assertTrue(session.finished)
    }

    @Test
    fun listeningPlaysPerBlueprintAndHidesQuestionsUntilPlayed() {
        val (items, passages) = pool(Skill.LISTENING)
        val form = ExamAssembler.test(blueprint, Skill.LISTENING, FormLength.SLICE_30, items, passages, emptySet(), Random(1))
        val session = ExamSession(form, PlayPolicy(plays = 1, questionsVisibleBeforeAudio = false), TestClock())
        val pid = session.current!!.item.passageId!!
        assertFalse(session.questionsVisible(pid))
        assertTrue(session.canPlayAudio(pid))
        session.audioPlayed(pid)
        assertFalse(session.canPlayAudio(pid))
        assertTrue(session.questionsVisible(pid))
        val twice = ExamSession(form, PlayPolicy(plays = 2, questionsVisibleBeforeAudio = true), TestClock())
        assertTrue(twice.questionsVisible(pid))
        twice.audioPlayed(pid)
        assertTrue(twice.canPlayAudio(pid))
    }

    @Test
    fun practiceIsUntimedAndReplayable() {
        val (items, passages) = pool(Skill.LISTENING)
        val form = ExamAssembler.practice("xx", Skill.LISTENING, "2", null, items, passages, emptySet(), Random(1))
        assertEquals(ExamMode.PRACTICE, form.mode)
        assertEquals(3, form.items.size)
        val session = ExamSession(form, PlayPolicy(plays = 1), TestClock())
        assertNull(session.remainingMs())
        val pid = form.passageIds.single()
        repeat(3) { session.audioPlayed(pid) }
        assertTrue(session.canPlayAudio(pid))
        assertTrue(session.questionsVisible(pid))
    }

    @Test
    fun practicePrefersUnseenPassages() {
        val (items, passages) = pool(perLevel = 2)
        val form = ExamAssembler.practice("xx", Skill.READING, "1", null, items, passages, setOf("1-1"), Random(1))
        assertEquals(listOf("1-2"), form.passageIds)
    }

    @Test
    fun scoringIsDeterministic() {
        val (items, passages) = pool()
        fun take(): ExamResult {
            val form = ExamAssembler.test(blueprint, Skill.READING, FormLength.FULL, items, passages, emptySet(), Random(42))
            val session = ExamSession(form, clock = TestClock())
            form.items.forEachIndexed { i, f ->
                session.goTo(i)
                // Right through ILR 2, wrong above.
                if (f.item.level in levels.take(4)) session.choose(f.item.answer) else session.choose((f.item.answer + 1) % 4)
            }
            return session.submit()
        }
        val a = take()
        val b = take()
        assertEquals(a.scoring, b.scoring)
        assertEquals("2", a.scoring.ilr)
        assertTrue(a.summary.contains("ILR 2"))
    }

    @Test
    fun unansweredCountsWrong() {
        val (items, passages) = pool()
        val session = ExamSession(ExamAssembler.test(blueprint, Skill.READING, FormLength.SLICE_30, items, passages, emptySet(), Random(1)), clock = TestClock())
        session.choose(session.current!!.item.answer)
        val result = session.submit()
        assertEquals(1, result.answers.count { it.correct })
    }
}
