package app.tsumugi.sync

import app.tsumugi.coverage.KnownWords
import app.tsumugi.coverage.LearnerKnowledge
import app.tsumugi.coverage.WordState
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** BRIEF_V2 §6.1: media decks, their words and known words sync; text profiles never leave the device (D-150, D-151). */
class Phase11SyncTest {
    private val clock = TestClock()
    private val server = FakeSyncServer()
    private val dictionary = DictionaryRepository(DictionaryFixture.create())

    private inner class Device(val id: String) {
        val driver = inMemoryDriver(TsumugiDatabase.Schema)
        val db = TsumugiDatabase(driver)
        val srs = SrsRepository(db, id, clock)
        val engine = SyncEngine(driver, db, srs, id, server, null, clock)
        val knowledge = LearnerKnowledge(db, srs)
        val known = KnownWords(db, knowledge, { dictionary }, clock)
    }

    private fun Device.insertDeck(deckId: String, title: String, at: Long) {
        db.decksQueries.insertDeck(deckId, title, "TEXT", null, 2, 5, "[]", "[]", "{}", at, at)
        db.decksQueries.insertDeckWordIfAbsent(deckId, DictionaryFixture.NEKO, 0, "猫", "ねこ", 3, 3.0, "猫が好き。", at)
        db.decksQueries.insertDeckWordIfAbsent(deckId, DictionaryFixture.SUSHI, 1, "寿司", "すし", 2, 2.0, null, at)
    }

    @Test
    fun decksWordsAndKnownWordsSync() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        a.insertDeck("deck-1", "Episode 1", 1_000)
        a.known.markKnown(listOf(DictionaryFixture.NEKO, DictionaryFixture.TABERU))
        b.known.markKnown(listOf(DictionaryFixture.SUSHI))
        a.db.decksQueries.putProfile("doc:x", "1:1", "{}", 0) // device-local cache

        a.engine.sync(); b.engine.sync(); a.engine.sync()

        for (d in listOf(a, b)) {
            assertEquals(listOf("Episode 1"), d.db.decksQueries.decks().executeAsList().map { it.title }, d.id)
            assertEquals(listOf(DictionaryFixture.NEKO, DictionaryFixture.SUSHI), d.db.decksQueries.deckWords("deck-1").executeAsList().map { it.entry_id }, d.id)
            assertEquals("猫が好き。", d.db.decksQueries.deckWords("deck-1").executeAsList().first().context)
            assertEquals(3, d.known.count(), d.id)
            assertEquals(WordState.KNOWN, d.knowledge.snapshot().word(DictionaryFixture.SUSHI), d.id)
        }
        assertEquals(0, b.db.decksQueries.profileFor("doc:x").executeAsList().size, "profiles are device-local")

        // The known flag is last-writer-wins: B unmarks 猫 later than A marked it.
        clock.advance(5.seconds)
        b.known.markUnknown(listOf(DictionaryFixture.NEKO))
        clock.advance(1.seconds)
        a.db.decksQueries.renameDeck("Episode 1 (JP)", clock.now().toEpochMilliseconds(), "deck-1")
        b.engine.sync(); a.engine.sync(); b.engine.sync()
        for (d in listOf(a, b)) {
            assertEquals(WordState.UNKNOWN, d.knowledge.snapshot().word(DictionaryFixture.NEKO), d.id)
            assertEquals(2, d.known.count(), d.id)
            assertEquals("Episode 1 (JP)", d.db.decksQueries.deckById("deck-1").executeAsOne().title, d.id)
        }

        // Deleting a deck is a tombstone that reaches the other device.
        clock.advance(1.seconds)
        val now = clock.now().toEpochMilliseconds()
        b.db.decksQueries.deleteDeck(now, "deck-1")
        b.db.decksQueries.deleteDeckWords(now, "deck-1")
        b.engine.sync(); a.engine.sync()
        assertEquals(0, a.db.decksQueries.decks().executeAsList().size)
        assertEquals(0, a.db.decksQueries.deckWords("deck-1").executeAsList().size)
    }
}
