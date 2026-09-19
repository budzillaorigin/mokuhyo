package app.tsumugi.study.games

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.l10n.AppLocale
import app.tsumugi.practice.PracticeRepository
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.study.ChallengeKind
import app.tsumugi.study.TodayPlanner
import app.tsumugi.study.activities.Activity
import app.tsumugi.study.activities.PomodoroSession
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GamesTest {

    private val words = listOf(
        GameWord("1", "猫", "ねこ", "cat"),
        GameWord("2", "犬", "いぬ", "dog"),
        GameWord("3", "水", "みず", "water"),
        GameWord("4", "学校", "がっこう", "school"),
        GameWord("5", "コーヒー", "コーヒー", "coffee"),
        GameWord("6", "食べる", "たべる", "to eat"),
    )

    @Test
    fun reflexScoringStreaksAndTimeouts() {
        val clock = TestClock()
        val game = ReflexGame(words, Random(1), clock)
        var expectedScore = 0
        repeat(6) { i ->
            val card = assertNotNull(game.next())
            assertEquals(card.isMatch, card.shownMeaning == card.word.meaning)
            if (!card.isMatch) assertTrue(card.shownMeaning != card.word.meaning)
            clock.advance(3000.milliseconds.coerceAtMost(card.timeLimit / 4))
            val out = assertNotNull(game.answer(card.isMatch))
            assertTrue(out.correct)
            assertEquals(i + 1, out.streak)
            expectedScore += out.points
            assertTrue(out.points >= ReflexGame.BASE_POINTS)
        }
        assertEquals(expectedScore, game.score)
        assertEquals(2 * ReflexGame.BASE_POINTS, ReflexGame.points(6, 1.seconds, 1.seconds), "the sixth in a row scores ×2")
        assertEquals(ReflexGame.BASE_POINTS + ReflexGame.MAX_SPEED_BONUS, ReflexGame.points(1, 0.milliseconds, 2.seconds))

        // A wrong answer resets the streak and scores nothing; so does a late one.
        val wrongCard = assertNotNull(game.next())
        val wrong = assertNotNull(game.answer(!wrongCard.isMatch))
        assertFalse(wrong.correct)
        assertEquals(0, wrong.points)
        assertEquals(0, game.streak)
        val late = assertNotNull(game.next())
        clock.advance(late.timeLimit + 1.milliseconds)
        val timedOut = assertNotNull(game.answer(late.isMatch))
        assertTrue(timedOut.timedOut && !timedOut.correct)
        assertEquals(6, game.bestStreak)

        assertTrue(ReflexGame.timeLimit(0) > ReflexGame.timeLimit(10), "cards speed up with the streak")
        assertEquals(1500.milliseconds, ReflexGame.timeLimit(100))

        clock.advance(ReflexGame.DEFAULT_ROUND)
        assertTrue(game.finished)
        assertNull(game.next())
        val result = game.result()
        assertEquals(GameKind.REFLEX, result.game)
        assertEquals(8, result.total)
        assertEquals(6, result.correct)
        assertEquals(ReflexGame.DEFAULT_ROUND.inWholeMilliseconds, result.durationMs)
        assertTrue(ReflexGame(words.take(3)).finished, "too few words to play")
    }

    @Test
    fun atomAssemblesKanaUnderTime() {
        val clock = TestClock()
        val game = AtomGame(words, Random(2), clock)
        val puzzle = assertNotNull(game.next())
        assertTrue(puzzle.tiles.size > puzzle.target.size, "decoys are mixed in")
        assertTrue(puzzle.target.all { t -> puzzle.tiles.any { AtomGame.sameKana(it.kana, t) } })
        // A wrong tile first, then the right ones in order.
        val decoy = puzzle.tiles.first { tile -> puzzle.target.none { AtomGame.sameKana(it, tile.kana) } }
        assertEquals(AtomTapResult.WRONG, game.tap(decoy.id)?.result)
        var last: AtomState? = null
        val free = puzzle.tiles.toMutableList()
        for (mora in puzzle.target) {
            val tile = free.first { AtomGame.sameKana(it.kana, mora) }
            free.remove(tile)
            clock.advance(200.milliseconds)
            last = game.tap(tile.id)
        }
        assertEquals(AtomTapResult.SOLVED, last?.result)
        assertEquals(puzzle.target, last?.assembled)
        assertEquals(1, last?.mistakes)
        assertEquals(0, game.streak, "a mistake keeps the streak at zero")
        assertTrue(game.score >= AtomGame.POINTS_PER_MORA * puzzle.target.size / 2)

        // Out of time: no points.
        val next = assertNotNull(game.next())
        clock.advance(next.timeLimit + 1.milliseconds)
        assertEquals(AtomTapResult.TIMED_OUT, game.tap(next.tiles.first().id)?.result)
        assertEquals(2, game.result().total)
        assertEquals(1, game.result().correct)

        assertEquals(AtomGame.POINTS_PER_MORA * 3 + AtomGame.MAX_TIME_BONUS, AtomGame.points(3, 0, 0.milliseconds, 5.seconds))
        assertEquals(AtomGame.POINTS_PER_MORA * 3 / 2, AtomGame.points(3, 20, 5.seconds, 5.seconds), "never below half")
        assertEquals("が", AtomGame.lookAlike("か"))
        assertEquals("ツ", AtomGame.lookAlike("ッ"))
        assertTrue(AtomGame(listOf(GameWord("x", "x", "abc", "x"))).finished, "readings must be kana")
    }

    @Test
    fun scoresFeedTheWeeklyChallenge() = runTest {
        // The week of 2026-10-19 is the sixth rotation slot on its games turn (D-287).
        val clock = TestClock(Instant.parse("2026-10-20T09:00:00Z"))
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val scores = GameScores(db, "device", clock) { TimeZone.UTC }
        assertNull(scores.record(GameResult(GameKind.REFLEX, 0, 0, 0, 0, 1000)), "an empty round isn't stored")
        scores.record(GameResult(GameKind.REFLEX, 340, 20, 24, 9, 60_000))
        scores.record(GameResult(GameKind.ATOM, 210, 7, 9, 3, 90_000))
        assertEquals(550, scores.weekPoints())
        assertEquals(340, scores.best(GameKind.REFLEX))
        assertEquals(210, scores.best(GameKind.ATOM))
        assertEquals(listOf(210), scores.recent(GameKind.ATOM).map { it.score })
        assertEquals(0, scores.weekPoints(LocalDate(2026, 10, 27)), "next week starts from zero")

        val settings = SettingsRepository(db, clock)
        val week = (0 until 12).map { w ->
            val planner = TodayPlanner(db, settings, TestClock(Instant.parse("2026-09-07T09:00:00Z") + (7 * w).days)) { TimeZone.UTC }
            planner.plan(0, null, 0, locale = AppLocale.EN).challenge
        }
        val games = week.filter { it.kind == ChallengeKind.GAME_POINTS }
        assertEquals(1, games.size, "one week in twelve")
        assertEquals(TodayPlanner.GAME_POINTS_GOAL, games.single().goal)
        assertTrue(week.any { it.kind == ChallengeKind.NEW_ITEMS }, "the slot still alternates with new items")
        val thisWeek = TodayPlanner(db, settings, clock) { TimeZone.UTC }.plan(0, null, 0, locale = AppLocale.EN).challenge
        assertEquals(ChallengeKind.GAME_POINTS, thisWeek.kind)
        assertEquals(550, thisWeek.progress)
        assertEquals("Score 1500 points in Reflex and Atom this week", thisWeek.title)
    }

    @Test
    fun gamesJoinThePomodoroQueue() = runTest {
        val session = assertNotNull(PomodoroSession.build(null, 4, Random(1), gameWords = words))
        assertEquals(listOf("Reflex", "Atom"), session.activities.map { it.title })
        val reflex = session.activities.first() as Activity.Reflex
        assertEquals(Activity.POMODORO_ROUND, reflex.game().roundLength)
        assertNull(PomodoroSession.build(null as PracticeRepository?, 4, Random(1)), "no pack and no words: nothing to do")
        assertEquals(listOf("猫", "犬"), GameWords.pool(words.take(2), words.take(1), 10).map { it.text })
    }
}
