package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.Stage
import app.tsumugi.l10n.AppLocale
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.PathStatus
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** BRIEF_V2 G-01: the extra Today blocks by budget, their launch payloads, and the review cap. */
class TodayBlocksTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val settings = SettingsRepository(db, clock)
    private val planner = TodayPlanner(db, settings, clock) { TimeZone.UTC }
    private val status = PathStatus(12, 60, 0.2, 40, 0, emptyMap<Stage, Int>())

    /** Test data only: made-up ids and titles. */
    private val candidates = TodayCandidates(
        learnerJlpt = 4,
        immersion = listOf(
            ImmersionCandidate(ImmersionSource.READER, "doc-n1", "Hard editorial", jlpt = 1),
            ImmersionCandidate(ImmersionSource.DIALOGUE, "dlg-n4", "At the station", jlpt = 4, levelLabel = "N4", aiGenerated = true),
            ImmersionCandidate(ImmersionSource.READER, "doc-n4-done", "Finished story", jlpt = 4, finished = true),
        ),
        shadowing = (1..6).map { ShadowingSentence("文$it。", "Sentence $it.", "n4-teoku", "〜ておく", false) },
        scenarios = listOf(ScenarioCandidate("konbini", "At the convenience store", 5, true), ScenarioCandidate("doctor", "At the doctor", 4, false)),
        writingKanji = listOf("日", "月", "火", "水"),
    )

    private suspend fun planAt(budget: Int, due: Int = 30) = settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, budget.toString()).let {
        planner.plan(due, status, grammarAvailable = 3, candidates = candidates, locale = AppLocale.EN)
    }

    @Test
    fun twentyMinutesAddsImmersionAndShadowing() = runTest {
        val plan = planAt(20)
        assertEquals(
            listOf(TodayBlockKind.REVIEWS, TodayBlockKind.LESSONS, TodayBlockKind.GRAMMAR, TodayBlockKind.IMMERSION, TodayBlockKind.SHADOWING),
            plan.blocks.map { it.kind },
        )
        val immersion = assertIs<TodayLaunch.Immersion>(plan.block(TodayBlockKind.IMMERSION)!!.launch)
        assertEquals("dlg-n4", immersion.target.id, "the candidate at the learner's level wins; finished texts are skipped")
        assertEquals(5, plan.block(TodayBlockKind.IMMERSION)!!.minutes)
        val shadowing = assertIs<TodayLaunch.Shadowing>(plan.block(TodayBlockKind.SHADOWING)!!.launch)
        assertEquals(3, shadowing.sentences.size)
    }

    @Test
    fun fortyMinutesAddsTheSpeakingMoment() = runTest {
        val plan = planAt(40)
        assertEquals(
            listOf(
                TodayBlockKind.REVIEWS, TodayBlockKind.LESSONS, TodayBlockKind.GRAMMAR, TodayBlockKind.IMMERSION,
                TodayBlockKind.SHADOWING, TodayBlockKind.SPEAKING,
            ),
            plan.blocks.map { it.kind },
        )
        val speaking = plan.block(TodayBlockKind.SPEAKING)!!
        assertTrue(speaking.optional)
        assertEquals("doctor", assertIs<TodayLaunch.Speaking>(speaking.launch).scenarioId, "scenario at the learner's JLPT level")
        assertEquals(4, (plan.block(TodayBlockKind.SHADOWING)!!.launch as TodayLaunch.Shadowing).sentences.size)
        assertEquals(8, plan.block(TodayBlockKind.IMMERSION)!!.minutes)
    }

    @Test
    fun sixtyMinutesRunsAllSixKindsOfBlocks() = runTest {
        val plan = planAt(60)
        assertEquals(
            listOf(
                TodayBlockKind.REVIEWS, TodayBlockKind.LESSONS, TodayBlockKind.GRAMMAR, TodayBlockKind.IMMERSION,
                TodayBlockKind.SHADOWING, TodayBlockKind.SPEAKING, TodayBlockKind.WRITING,
            ),
            plan.blocks.map { it.kind },
        )
        val writing = assertIs<TodayLaunch.Writing>(plan.block(TodayBlockKind.WRITING)!!.launch)
        assertEquals(3, writing.kanji.size)
        assertEquals(5, (plan.block(TodayBlockKind.SHADOWING)!!.launch as TodayLaunch.Shadowing).sentences.size)
        assertEquals(listOf(5, 10, 15, 20), plan.timerOptions, "a Pomodoro can wrap any block")
        assertEquals(
            plan.blocks.filterNot { it.optional || it.done }.sumOf { it.minutes }, plan.plannedMinutes,
            "optional blocks don't count against the budget",
        )
    }

    @Test
    fun tenMinutesHasNoExtraBlocks() = runTest {
        val plan = planAt(10)
        assertEquals(listOf(TodayBlockKind.REVIEWS, TodayBlockKind.LESSONS, TodayBlockKind.GRAMMAR), plan.blocks.map { it.kind })
    }

    @Test
    fun reviewCapComesFromTheBudgetAndCountsTodaysAnswers() = runTest {
        val plan = planAt(20, due = 400)
        val cap = planner.reviewCap(20, LearningPhase.CORE) // 20 min × 6/min × 0.6 = 72
        assertEquals(72, cap)
        assertEquals(cap, plan.reviewLimit)
        assertEquals(TodayLaunch.Reviews(cap), plan.blocks.first().launch)

        // Answer 50 today: the cap left is 22, whatever is due.
        val srs = SrsRepository(db, "device", clock)
        srs.addItems(listOf(NewItem("k:水", ItemKind.KANJI, "水", "すい", listOf("water"), listOf("すい"), ItemSource.PACK, listOf(CardDirection.MEANING))))
        val card = SrsRepository.cardId("k:水", CardDirection.MEANING)
        srs.introduce(listOf(card))
        repeat(50) { srs.review(card, Rating.GOOD, correct = true) }
        assertEquals(cap - 50, planAt(20, due = 400).reviewLimit)

        repeat(30) { srs.review(card, Rating.GOOD, correct = true) }
        val spent = planAt(20, due = 400)
        assertEquals(0, spent.reviewLimit, "budget used up: nothing more to start today")
        assertTrue(spent.blocks.first().done)
        assertNull(spent.blocks.first().launch)
    }

    @Test
    fun finishedBlocksAreRecordedAndCountForBlockChallenges() = runTest {
        clock.advance(27.days) // Monday 2026-09-28: the week of the "finish Today blocks" challenge
        val before = planAt(20)
        assertEquals(ChallengeKind.BLOCKS, before.challenge.kind)
        assertEquals(0, before.challenge.progress)

        planner.markDone(TodayBlockKind.IMMERSION)
        planner.markDone(TodayBlockKind.SHADOWING)
        planner.markDone(TodayBlockKind.SHADOWING) // idempotent
        val after = planAt(20)
        assertTrue(after.block(TodayBlockKind.IMMERSION)!!.done)
        assertNull(after.block(TodayBlockKind.IMMERSION)!!.launch)
        assertEquals(2, after.challenge.progress)

        clock.advance(1.days)
        assertTrue(!planAt(20).block(TodayBlockKind.IMMERSION)!!.done, "a new day starts fresh")
        assertEquals(2, planAt(20).challenge.progress, "the week still counts yesterday's blocks")
    }

    @Test
    fun blockTitlesComeFromTheSharedStringTable() = runTest {
        settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, "60")
        val ja = planner.plan(30, status, 3, candidates, AppLocale.JA)
        assertEquals("シャドーイング", ja.block(TodayBlockKind.SHADOWING)!!.title)
        assertEquals("30件", ja.blocks.first().detail)
        val en = planner.plan(30, status, 3, candidates, AppLocale.EN)
        assertEquals("Speaking moment", en.block(TodayBlockKind.SPEAKING)!!.title)
        assertEquals("30 due", en.blocks.first().detail)
    }

    @Test
    fun simpleDifficultyPrefersTheLearnersLevelAndPenalizesHarderText() {
        val d = SimpleImmersionDifficulty
        fun c(jlpt: Int?, known: Double? = null) = ImmersionCandidate(ImmersionSource.READER, "x", "x", jlpt, known)
        assertEquals(0.0, d.mismatch(c(4), 4))
        assertEquals(1.0, d.mismatch(c(5), 4), "easier by one")
        assertEquals(1.5, d.mismatch(c(3), 4), "harder by one counts 1.5×")
        assertEquals(1.0, d.mismatch(c(3, known = 0.95), 4), "mostly known words make it easier")
        assertEquals(2.0, d.mismatch(c(null), 4))
    }

    @Test
    fun focusTimerRunsWorkThenBreak() {
        val t = FocusTimer.forBlock(TodayBlockKind.WRITING, 10, clock)
        assertEquals(10.minutes, t.remaining)
        clock.advance(10.minutes)
        assertTrue(t.onBreak)
        assertEquals(2.minutes, t.breakRemaining)
        clock.advance(2.minutes)
        assertTrue(t.finished)
    }

    @Test
    fun learnerLevelFromPathLevel() {
        assertEquals(5, LearnerLevel.jlptForPathLevel(1))
        assertEquals(4, LearnerLevel.jlptForPathLevel(12))
        assertEquals(3, LearnerLevel.jlptForPathLevel(30))
        assertEquals(1, LearnerLevel.jlptForPathLevel(60))
    }

    @Test
    fun kanaCourseReplacesPathLessonsUntilDone() = runTest {
        settings.put(SettingsRepository.DAILY_BUDGET_MINUTES, "20")
        val plan = planner.plan(0, status, grammarAvailable = 0, candidates = candidates, locale = AppLocale.EN, kanaLessons = 30)
        val lessons = plan.blocks.single { it.kind == TodayBlockKind.LESSONS }
        val launch = assertIs<TodayLaunch.Kana>(lessons.launch)
        assertTrue(launch.count in 1..30)
        assertTrue(lessons.detail.startsWith("Kana"), lessons.detail)
        val later = planner.plan(0, status, grammarAvailable = 0, candidates = candidates, locale = AppLocale.EN, kanaLessons = 0)
        assertIs<TodayLaunch.Lessons>(later.blocks.single { it.kind == TodayBlockKind.LESSONS }.launch)
    }
}
