package app.tsumugi.srs

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.domain.Stage
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class SrsRepositoryTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, deviceId = "device-a", clock = clock)

    private val kanji = NewItem(
        id = "k:語", kind = ItemKind.KANJI, primaryText = "語", reading = "ご",
        meanings = listOf("word", "language"), acceptedReadings = listOf("ご", "かた"),
        source = ItemSource.PACK, directions = listOf(CardDirection.MEANING, CardDirection.READING), level = 8,
    )
    private val meaning = SrsRepository.cardId("k:語", CardDirection.MEANING)
    private val reading = SrsRepository.cardId("k:語", CardDirection.READING)

    @Test
    fun addedItemsAwaitALesson() = runTest {
        srs.addItems(listOf(kanji))
        srs.addItems(listOf(kanji)) // idempotent
        assertEquals(2, srs.cardsForItems(listOf("k:語")).size)
        assertTrue(srs.cardsForItems(listOf("k:語")).all { it.fsrs.state == CardState.NEW })
        assertEquals(0, srs.dueCount(), "NEW cards are not reviews")
        assertEquals(listOf("word", "language"), srs.item("k:語")!!.meanings)
    }

    @Test
    fun introducedCardsBecomeDueAfterFirstLearningStep() = runTest {
        srs.addItems(listOf(kanji))
        srs.introduce(listOf(meaning, reading))
        assertEquals(0, srs.dueCount())
        clock.advance(10.minutes)
        assertEquals(2, srs.dueCount())
        assertEquals(CardState.LEARNING, srs.card(meaning)!!.fsrs.state)
    }

    @Test
    fun reviewsMoveCardsThroughStagesAndUndoRestores() = runTest {
        srs.addItems(listOf(kanji))
        srs.introduce(listOf(meaning, reading))
        clock.advance(10.minutes)
        val first = srs.review(meaning, Rating.GOOD)
        assertEquals(CardState.LEARNING, first.after.fsrs.state)
        srs.undo(first)
        assertEquals(first.before.fsrs, srs.card(meaning)!!.fsrs)

        srs.review(meaning, Rating.GOOD)
        clock.advance(1.days)
        srs.review(meaning, Rating.GOOD)
        assertEquals(CardState.REVIEW, srs.card(meaning)!!.fsrs.state)
        assertEquals(Stage.APPRENTICE, srs.stages()["k:語"], "reading card is still apprentice")
    }

    @Test
    fun replayReproducesIncrementalState() = runTest {
        srs.addItems(listOf(kanji))
        srs.introduce(listOf(meaning))
        val ratings = listOf(Rating.GOOD, Rating.GOOD, Rating.AGAIN, Rating.GOOD, Rating.EASY, Rating.HARD, Rating.GOOD)
        for (r in ratings) {
            clock.advance(srs.card(meaning)!!.fsrs.due - clock.now + 1.minutes)
            srs.review(meaning, r)
        }
        val incremental = srs.card(meaning)!!.fsrs
        srs.recomputeCard(meaning)
        assertEquals(incremental, srs.card(meaning)!!.fsrs)
        assertEquals(1, incremental.lapses)
    }

    @Test
    fun importedReviewsAreIdempotentAndReplayed() = runTest {
        srs.addItems(listOf(kanji))
        val t0 = clock.now()
        val log = listOf(
            ImportedReview(meaning, t0, Rating.GOOD, "1", "anki"),
            ImportedReview(meaning, t0 + 1.days, Rating.GOOD, "2", "anki"),
            ImportedReview(meaning, t0 + 5.days, Rating.GOOD, "3", "anki"),
        )
        srs.importReviews(log)
        srs.importReviews(log)
        val card = assertNotNull(srs.card(meaning)).fsrs
        assertEquals(3, card.reps)
        assertEquals(CardState.REVIEW, card.state)
        assertEquals(3, srs.reviewLogForOptimizer().size, "lesson markers excluded; duplicates ignored")
    }

    @Test
    fun notesKeepMyStoryAndSynonyms() = runTest {
        srs.addItems(listOf(kanji))
        srs.saveNote("k:語", myStory = "Five mouths saying words")
        srs.saveNote("k:語", synonyms = listOf("tongue"))
        val item = srs.item("k:語")!!
        assertEquals("Five mouths saying words", item.myStory)
        assertEquals(listOf("tongue"), item.synonyms)
    }
}
