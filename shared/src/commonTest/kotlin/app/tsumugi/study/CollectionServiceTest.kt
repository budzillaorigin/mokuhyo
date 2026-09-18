package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.srs.CardState
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CollectionServiceTest {

    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(db, "device", clock)
    private val dictionary = DictionaryRepository(DictionaryFixture.create())
    private val collection = CollectionService(db, srs, { null }, clock)

    @Test
    fun addDictionaryWordToReviews() = runTest {
        val entry = dictionary.entry(DictionaryFixture.TABERU)!!.entry
        assertFalse(collection.isInReviews(entry.id))
        val id = collection.addToReviews(entry, context = "寿司を食べる。")
        assertEquals("jmdict:${DictionaryFixture.TABERU}", id)
        assertTrue(collection.isInReviews(entry.id))
        val item = srs.item(id)!!
        assertEquals("食べる", item.primaryText)
        assertEquals(listOf("たべる"), item.acceptedReadings)
        assertEquals("寿司を食べる。", item.context)
        assertTrue(srs.cardsForItems(listOf(id)).all { it.fsrs.state == CardState.LEARNING })
    }

    @Test
    fun wordLists() = runTest {
        val listId = collection.createList("Food")
        val summary = dictionary.summaries(listOf(DictionaryFixture.TABERU, DictionaryFixture.SUSHI))
        summary.forEach {
            collection.addToList(listId, it)
            clock.advance(1.seconds)
        }
        collection.addToList(listId, summary.first()) // re-adding is idempotent
        assertEquals(listOf(WordListSummary(listId, "Food", 2)), collection.lists())
        assertEquals(listOf("食べる", "寿司"), collection.entries(listId).map { it.text })
        assertEquals(DictionaryFixture.SUSHI, collection.entries(listId).last().entryId)
        assertEquals(listOf(listId), collection.listsContaining(DictionaryFixture.SUSHI))
        collection.removeFromList(listId, "jmdict:${DictionaryFixture.SUSHI}")
        assertEquals(1, collection.entries(listId).size)
        collection.deleteList(listId)
        assertTrue(collection.lists().isEmpty())
    }
}
