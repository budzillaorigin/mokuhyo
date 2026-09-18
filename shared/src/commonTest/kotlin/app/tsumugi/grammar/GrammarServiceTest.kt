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
}
