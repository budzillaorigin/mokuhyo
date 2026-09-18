package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.practice.MinimalPairCategory
import app.tsumugi.practice.PracticeRepository
import app.tsumugi.practice.db.Minimal_pair
import app.tsumugi.practice.db.PracticeDatabase
import app.tsumugi.srs.CardState
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.Verdict
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

/** BRIEF_V2 G-06: minimal pairs become MINIMAL_PAIR cards on FSRS when the learner starts the drill. */
class MinimalPairServiceTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)

    /** Rows shaped like build_practice.py output. Test data only. */
    private val practice = PracticeRepository(
        PracticeDatabase(inMemoryDriver(PracticeDatabase.Schema)).also { pdb ->
            val q = pdb.practiceQueries
            fun pair(id: Long, cat: String, a: String, ra: String, b: String, rb: String, rank: Long, accA: Long? = null, accB: Long? = null) =
                q.insertPair(Minimal_pair(id, cat, 10 + id, a, ra, accA, "gloss a", 20 + id, b, rb, accB, "gloss b", rank))
            pair(1, "LENGTH", "おばさん", "おばさん", "おばあさん", "おばあさん", 1)
            pair(2, "GEMINATION", "きて", "きて", "きって", "きって", 2)
            pair(3, "PITCH", "箸", "はし", "橋", "はし", 3, 1, 2)
        },
    )
    private val service = MinimalPairService(practice, srs, clock)

    @Test
    fun startingTheDrillCreatesCardsScheduledByFsrs() = runTest {
        assertEquals(0, service.cardCount())
        val session = service.startDrill(newPairs = 2, random = Random(3))
        assertEquals(2, service.cardCount())
        val item = assertNotNull(srs.item("mp:1"))
        assertEquals(ItemKind.MINIMAL_PAIR, item.kind)
        assertEquals(CardState.LEARNING, srs.card(SrsRepository.cardId("mp:1", CardDirection.LISTENING))!!.fsrs.state)
        assertTrue(session.isEmpty, "just introduced: due after the first learning step")

        clock.advance(10.minutes)
        val due = service.session(random = Random(3))
        val asking = assertIs<ReviewState.Asking>(due.state.value)
        assertEquals(AnswerMode.MINIMAL_PAIR, asking.prompt.mode)
        val pair = assertNotNull(asking.prompt.minimalPair)
        due.submit(if (pair.playA) "a" else "b")
        val answered = assertIs<ReviewState.Answered>(due.state.value)
        assertEquals(Verdict.CORRECT, answered.verdict)

        // Scheduled like everything else: they come due in normal reviews too.
        clock.advance(2.days)
        val general = ReviewSession.start(srs, clock = clock, random = Random(1))
        val prompt = assertIs<ReviewState.Asking>(general.state.value).prompt
        assertEquals(AnswerMode.MINIMAL_PAIR, prompt.mode)
    }

    @Test
    fun drillsAddOnlyNewPairsOfTheChosenCategory() = runTest {
        service.startDrill(MinimalPairCategory.PITCH, newPairs = 5)
        assertEquals(1, service.cardCount())
        service.startDrill(MinimalPairCategory.PITCH, newPairs = 5)
        assertEquals(1, service.cardCount(), "no duplicates")
        service.startDrill(newPairs = 5)
        assertEquals(3, service.cardCount())
    }

    @Test
    fun answersByLetterWordOrReading() {
        val card = MinimalPairCard(1, "LENGTH", PairSide("おばさん", "おばさん"), PairSide("おばあさん", "おばあさん"))
        val playsB = MinimalPairPrompt(card, playA = false)
        assertEquals("おばあさん", playsB.answer)
        assertEquals(Verdict.CORRECT, playsB.check("b").verdict)
        assertEquals(Verdict.CORRECT, playsB.check("1").verdict)
        assertEquals(Verdict.CORRECT, playsB.check("おばあさん").verdict)
        assertEquals(Verdict.WRONG, playsB.check("a").verdict)

        val pitch = MinimalPairPrompt(MinimalPairCard(3, "PITCH", PairSide("箸", "はし", 1), PairSide("橋", "はし", 2)), playA = true)
        assertEquals(Verdict.CORRECT, pitch.check("箸").verdict)
        assertEquals(Verdict.WRONG, pitch.check("はし").verdict, "same reading: only the word tells them apart")
    }
}
