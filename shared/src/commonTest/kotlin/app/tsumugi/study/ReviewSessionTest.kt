package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.srs.CardState
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.Rating
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.Verdict
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class ReviewSessionTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)

    private suspend fun session(vararg items: NewItem): ReviewSession {
        srs.addItems(items.toList())
        srs.introduce(items.flatMap { i -> i.directions.map { SrsRepository.cardId(i.id, it) } })
        clock.advance(10.minutes)
        val cards = srs.dueCards()
        return ReviewSession(srs, cards, srs.items(cards.map { it.itemId }.toSet()), clock, Random(1))
    }

    private fun kanji(id: String, text: String, meaning: String, reading: String) = NewItem(
        id, ItemKind.KANJI, text, reading, listOf(meaning), listOf(reading), ItemSource.PACK,
        listOf(CardDirection.MEANING, CardDirection.READING),
    )

    private suspend fun ReviewSession.answerCorrectly() {
        val p = (state.value as ReviewState.Asking).prompt
        submit(if (p.mode == AnswerMode.MEANING) p.item.meanings.first() else p.item.acceptedReadings.first())
    }

    @Test
    fun correctAnswersFinishWithFullAccuracy() = runTest {
        val s = session(kanji("k:日", "日", "sun", "にち"), kanji("k:月", "月", "moon", "げつ"))
        repeat(4) {
            s.answerCorrectly()
            assertTrue((s.state.value as ReviewState.Answered).correct)
            s.next()
        }
        val done = assertIs<ReviewState.Finished>(s.state.value)
        assertEquals(4, done.summary.reviewed)
        assertEquals(1.0, done.summary.accuracy)
        assertEquals(0, srs.dueCount())
    }

    @Test
    fun wrongAnswerIsRecordedOnceAndReaskedAsPractice() = runTest {
        val s = session(kanji("k:日", "日", "sun", "にち"))
        val first = (s.state.value as ReviewState.Asking).prompt
        s.submit("wrong")
        assertEquals(Verdict.WRONG, (s.state.value as ReviewState.Answered).verdict)
        s.next()
        s.answerCorrectly()
        s.next()
        val practice = (s.state.value as ReviewState.Asking).prompt
        assertEquals(first.card.id, practice.card.id)
        assertTrue(practice.practice)
        s.answerCorrectly()
        s.next()
        val summary = assertIs<ReviewState.Finished>(s.state.value).summary
        assertEquals(2, summary.reviewed, "practice answers are not recorded")
        assertEquals(1, summary.correct)
        assertEquals(listOf("k:日"), summary.missed.map { it.id })
        assertEquals(CardState.LEARNING, srs.card(first.card.id)!!.fsrs.state, "AGAIN resets to step 0")
    }

    @Test
    fun typoIsCloseAndCountsAsCorrect() = runTest {
        val s = session(NewItem("k:山", ItemKind.KANJI, "山", "さん", listOf("mountain"), listOf("さん"), ItemSource.PACK, listOf(CardDirection.MEANING)))
        s.submit("mountian")
        val answered = assertIs<ReviewState.Answered>(s.state.value)
        assertEquals(Verdict.CLOSE, answered.verdict)
        assertEquals("mountain", answered.matched)
    }

    @Test
    fun undoRestoresCardAndReasks() = runTest {
        val s = session(NewItem("k:山", ItemKind.KANJI, "山", "さん", listOf("mountain"), listOf("さん"), ItemSource.PACK, listOf(CardDirection.MEANING)))
        val cardId = (s.state.value as ReviewState.Asking).prompt.card.id
        val before = srs.card(cardId)!!.fsrs
        s.submit("river")
        s.undo()
        assertEquals(before, srs.card(cardId)!!.fsrs)
        assertIs<ReviewState.Asking>(s.state.value)
        s.submit("mountain")
        s.next()
        assertEquals(1, assertIs<ReviewState.Finished>(s.state.value).summary.reviewed)
    }

    @Test
    fun readingAcceptsRomaji() = runTest {
        val s = session(NewItem("v:1", ItemKind.VOCAB, "学校", "がっこう", listOf("school"), listOf("がっこう"), ItemSource.PACK, listOf(CardDirection.READING)))
        s.submit("gakkou")
        assertTrue((s.state.value as ReviewState.Answered).correct)
    }

    @Test
    fun selfGradedCards() = runTest {
        val s = session(NewItem("anki:1", ItemKind.CUSTOM, "猫", "ねこ", listOf("cat"), emptyList(), ItemSource.ANKI, listOf(CardDirection.RECOGNITION)))
        val asking = assertIs<ReviewState.Asking>(s.state.value)
        assertEquals(AnswerMode.SELF_GRADED, asking.prompt.mode)
        s.reveal()
        assertIs<ReviewState.Revealed>(s.state.value)
        s.grade(Rating.EASY)
        assertEquals(CardState.REVIEW, srs.cardsForItems(listOf("anki:1")).single().fsrs.state)
        assertIs<ReviewState.Finished>(s.state.value)
    }

    @Test
    fun wrapUpLimitsRemainingCards() = runTest {
        val items = (1..20).map { NewItem("k:$it", ItemKind.RADICAL, "$it", null, listOf("m$it"), emptyList(), ItemSource.PACK, listOf(CardDirection.MEANING)) }
        val s = session(*items.toTypedArray())
        s.wrapUp(keep = 3)
        assertEquals(2, (s.state.value as ReviewState.Asking).remaining)
    }

    /** BRIEF_V2 F-09: a double tap (two concurrent submits) records exactly one review and advances exactly one card. */
    @Test
    fun concurrentSubmitsRecordOneReview() = runTest {
        val s = session(kanji("k:日", "日", "sun", "にち"), kanji("k:月", "月", "moon", "げつ"))
        val first = (s.state.value as ReviewState.Asking)
        val answer = if (first.prompt.mode == AnswerMode.MEANING) first.prompt.item.meanings.first() else first.prompt.item.acceptedReadings.first()
        val before = db.srsQueries.reviewCount().executeAsOne()
        coroutineScope {
            repeat(2) { launch(Dispatchers.Default) { s.submit(answer) } }
        }
        assertEquals(before + 1, db.srsQueries.reviewCount().executeAsOne(), "one review row")
        val answered = assertIs<ReviewState.Answered>(s.state.value)
        assertEquals(first.prompt.card.id, answered.prompt.card.id)
        assertEquals(first.remaining, answered.remaining, "exactly one card left the queue")
        assertEquals(1, answered.done)
    }

    /** A wrap-up requested while an answer is being written is applied right after it, not lost. */
    @Test
    fun wrapUpDuringSubmitIsDeferredNotLost() = runTest {
        val items = (1..20).map { NewItem("k:$it", ItemKind.RADICAL, "$it", null, listOf("m$it"), emptyList(), ItemSource.PACK, listOf(CardDirection.MEANING)) }
        val s = session(*items.toTypedArray())
        coroutineScope {
            launch(Dispatchers.Default) { s.answerCorrectly() }
            launch(Dispatchers.Default) { s.wrapUp(keep = 3) }
        }
        s.next()
        val asking = assertIs<ReviewState.Asking>(s.state.value)
        assertTrue(asking.remaining <= 3, "wrap-up applied: ${asking.remaining}")
        assertTrue(asking.wrappingUp)
    }
}
