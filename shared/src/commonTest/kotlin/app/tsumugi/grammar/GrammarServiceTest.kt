package app.tsumugi.grammar

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.grammar.db.GrammarDatabase
import app.tsumugi.grammar.db.Grammar_example
import app.tsumugi.grammar.db.Grammar_pattern
import app.tsumugi.grammar.db.Grammar_point
import app.tsumugi.srs.CardState
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.Verdict
import app.tsumugi.study.AnswerMode
import app.tsumugi.study.ReviewSession
import app.tsumugi.study.ReviewState
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class GrammarServiceTest {

    private val clock = TestClock()
    private val userDb = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(userDb, "device", clock)

    /** Two points shaped like build_grammar.py output. Test data only. */
    private val pack = GrammarDatabase(inMemoryDriver(GrammarDatabase.Schema)).also { db ->
        val q = db.grammarQueries
        q.insertPoint(Grammar_point("n4-teoku", 4, 1, "〜ておく", "Verb て-form + おく", "do in advance", "Doing something ahead of time.", "[]", "[]", "{}", "llm"))
        q.insertPoint(Grammar_point("n5-tai", 5, 1, "〜たい", "Verb stem + たい", "want to", "Speaker's desire.", "[]", "[]", "{}", "llm"))
        q.insertPattern(Grammar_pattern("n4-teoku", 0, "[てで](?:おく|おき|おい|おか|おこ|おけ)"))
        q.insertPattern(Grammar_pattern("n5-tai", 0, "たい|たく|たかっ"))
        fun ex(id: String, ord: Long, ja: String, en: String, answer: String) {
            val start = ja.indexOf(answer).toLong()
            q.insertExample(Grammar_example(id, ord, ja, en, start, start + answer.length, "tatoeba", 100 + ord))
        }
        ex("n4-teoku", 0, "切符を買っておいた。", "I bought the ticket in advance.", "ておいた")
        ex("n5-tai", 0, "水が飲みたい。", "I want to drink water.", "たい")
    }
    private val grammar = GrammarService(pack, srs) { null }

    @Test
    fun lessonQueueStartsAtEasiestLevel() = runTest {
        assertEquals(listOf("n5-tai", "n4-teoku"), grammar.lessonQueue(5).map { it.id })
        grammar.learn(grammar.lessonQueue(1))
        assertEquals(listOf("n4-teoku"), grammar.lessonQueue(5).map { it.id })
    }

    @Test
    fun clozeChecking() = runTest {
        val ex = assertNotNull(grammar.exercise("n4-teoku", Random(1)))
        assertEquals(ExerciseKind.CLOZE, ex.kind, "no dictionary → no BUILD exercises")
        assertEquals("切符を買っ＿＿＿。", ex.prompt)
        assertEquals(Verdict.CORRECT, grammar.check(ex, "ておいた").verdict)
        assertEquals(Verdict.CORRECT, grammar.check(ex, "teoita").verdict, "romaji converts")
        assertEquals(Verdict.CLOSE, grammar.check(ex, "ておきました").verdict, "another form of the same construction")
        assertEquals(Verdict.WRONG, grammar.check(ex, "てしまった").verdict)
        assertEquals(Verdict.WRONG, grammar.check(ex, "").verdict)
    }

    @Test
    fun missSpawnsGhostWhichRetiresAfterTwoCorrect() = runTest {
        grammar.learn(grammar.lessonQueue(2))
        clock.advance(10.minutes)

        suspend fun session() = ReviewSession.start(srs, grammar, clock = clock, random = Random(2))
        var s = session()
        while (true) {
            val asking = s.state.value as? ReviewState.Asking ?: break
            if (asking.prompt.practice) { s.finish(); break }
            assertEquals(AnswerMode.CLOZE, asking.prompt.mode)
            // Miss 〜ておく, get 〜たい right.
            s.submit(if (asking.prompt.item.id == "g:n4-teoku") "だめ" else asking.prompt.expected.first())
            s.next()
        }
        val ghostId = SrsRepository.cardId("g:n4-teoku", CardDirection.GHOST)
        assertEquals(CardState.LEARNING, srs.card(ghostId)!!.fsrs.state)
        assertTrue(srs.stages().containsKey("g:n4-teoku"), "ghosts don't hide the item's stage")

        // Two correct ghost answers (10 min, then 1 day) retire it.
        repeat(2) { round ->
            clock.advance(if (round == 0) 10.minutes else 1.days)
            s = session()
            while (true) {
                val asking = s.state.value as? ReviewState.Asking ?: break
                if (asking.prompt.practice) { s.finish(); break }
                s.submit(asking.prompt.expected.first())
                s.next()
            }
        }
        assertTrue(srs.card(ghostId)!!.suspended)
        assertIs<ReviewState.Finished>(s.state.value)
    }

    /** BRIEF_V2 F-20: undoing the miss that spawned a ghost takes the ghost back too. */
    @Test
    fun undoOfAMissRetractsTheGhostItSpawned() = runTest {
        grammar.learn(grammar.lessonQueue(2))
        clock.advance(10.minutes)
        val s = ReviewSession.start(srs, grammar, clock = clock, random = Random(2))
        val ghostId = SrsRepository.cardId("g:n4-teoku", CardDirection.GHOST)
        while (true) {
            val asking = assertIs<ReviewState.Asking>(s.state.value)
            if (asking.prompt.item.id == "g:n4-teoku") break
            s.submit(asking.prompt.expected.first())
            s.next()
        }
        s.submit("だめ")
        assertNotNull(srs.card(ghostId), "the miss spawned a ghost")
        s.undo()
        assertNull(srs.card(ghostId), "undo retracted the ghost")
        s.submit(assertIs<ReviewState.Asking>(s.state.value).prompt.expected.first())
        assertNull(srs.card(ghostId))
    }

    /** BRIEF_V2 F-20: a point with no example sentence can't be reviewed, so it must not count as due. */
    @Test
    fun pointsWithoutExamplesAreHeldOutOfReviews() = runTest {
        val packDb = GrammarDatabase(inMemoryDriver(GrammarDatabase.Schema))
        val q = packDb.grammarQueries
        q.insertPoint(Grammar_point("n5-tai", 5, 1, "〜たい", "Verb stem + たい", "want to", "", "[]", "[]", "{}", "llm"))
        q.insertPoint(Grammar_point("n5-desu", 5, 2, "〜です", "Noun + です", "to be", "", "[]", "[]", "{}", "llm"))
        q.insertExample(Grammar_example("n5-tai", 0, "水が飲みたい。", "I want to drink water.", 4, 6, "tatoeba", 1))
        val g = GrammarService(packDb, srs) { null }
        g.learn(g.lessonQueue(5))
        clock.advance(10.minutes)
        assertEquals(2, srs.dueCount())

        g.syncExampleAvailability() // what AppGraph runs when the pack is opened
        assertEquals(1, srs.dueCount(), "the point without examples is not due")
        assertEquals(listOf("n5-desu"), g.pointsWithoutExamples())
        assertTrue(g.points(5).single { it.point.id == "n5-desu" }.noExamples)
        val only = assertIs<ReviewState.Asking>(ReviewSession.start(srs, g, clock = clock).state.value)
        assertEquals("g:n5-tai", only.prompt.item.id)
        assertEquals(0, only.remaining)

        // A pack update that adds an example releases it.
        q.insertExample(Grammar_example("n5-desu", 0, "学生です。", "I am a student.", 2, 4, "tatoeba", 2))
        g.syncExampleAvailability()
        assertEquals(2, srs.dueCount())
        assertTrue(g.pointsWithoutExamples().isEmpty())
    }
}
