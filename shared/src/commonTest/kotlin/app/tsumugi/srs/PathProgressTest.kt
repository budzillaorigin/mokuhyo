package app.tsumugi.srs

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.path.db.PathDatabase
import app.tsumugi.path.db.Path_item
import app.tsumugi.path.db.Path_prereq
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/** BRIEF_V2 F-04 / CLAUDE.md rule 11: the path level and unlocks are persisted facts that lapses can't take back. */
class PathProgressTest {

    private val clock = TestClock()
    private val userDb = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val srs = SrsRepository(userDb, "device", clock)
    private val settings = SettingsRepository(userDb, clock)
    private val store = PathProgressStore(userDb, clock)

    /** Six levels, each: radical r:L, kanji k:L1..k:L3 built from it, vocab v:L written with k:L1. Test data only. */
    private val pathDb = PathDatabase(inMemoryDriver(PathDatabase.Schema)).also { db ->
        val q = db.pathQueries
        fun item(id: String, kind: String, level: Long, ord: Long) =
            q.insertItem(Path_item(id, kind, level, ord, id, id, id, "[\"$id\"]", "[\"よみ\"]", "[]", null, null, null))
        for (l in 1L..6L) {
            item("r:$l", "RADICAL", l, 1)
            for (k in 1..3) {
                item("k:$l$k", "KANJI", l, 1L + k)
                q.insertPrereq(Path_prereq("k:$l$k", "r:$l"))
            }
            item("v:$l", "VOCAB", l, 5)
            q.insertPrereq(Path_prereq("v:$l", "k:${l}1"))
        }
    }
    private val path = PathService(pathDb, srs, settings, store)

    private fun cards(itemId: String) = PathService.directionsFor(if (itemId.startsWith("r:")) app.tsumugi.domain.ItemKind.RADICAL else app.tsumugi.domain.ItemKind.KANJI)
        .map { SrsRepository.cardId(itemId, it) }

    /** Learns [itemIds] and forces their cards to [stability] days (Guru at ≥ 3, Apprentice below). */
    private suspend fun learnAt(stability: Double, vararg itemIds: String) {
        val items = path.items().filter { it.id in itemIds }
        if (srs.cardsForItems(itemIds.toList()).isEmpty()) path.completeLessons(items)
        for (id in itemIds) for (card in cards(id)) setStability(card, stability)
    }

    private fun setStability(cardId: String, stability: Double) {
        val now = clock.now().toEpochMilliseconds()
        userDb.srsQueries.updateCardState("REVIEW", null, stability, 5.0, now + 86_400_000, now - 10 * 86_400_000L, 3, 0, now, cardId)
    }

    private suspend fun passLevels(through: Int) {
        for (l in 1..through) learnAt(10.0, "r:$l", "k:${l}1", "k:${l}2", "k:${l}3")
    }

    @Test
    fun levelStaysPassedAfterLapsesAndLevelFiveLessonsStayAvailable() = runTest {
        passLevels(4)
        assertEquals(5, path.status().currentLevel)
        assertEquals(4, path.progress().passedLevel)
        val before = path.lessonQueue().map { it.id }
        assertTrue("r:5" in before && "v:4" in before, "level-5 radical and level-4 vocab are open: $before")

        // Lapse three kanji of level 4 (and the kanji v:4 is written with).
        for (k in listOf("k:41", "k:42", "k:43")) for (card in cards(k)) setStability(card, 0.5)

        val status = path.status()
        assertTrue(status.currentLevel >= 5, "level never drops: ${status.currentLevel}")
        assertEquals(4, path.progress().passedLevel)
        val after = path.lessonQueue().map { it.id }
        assertTrue("r:5" in after, "level-5 lessons stay available: $after")
        assertTrue("v:4" in after, "an unlocked lesson stays unlocked when its kanji lapses: $after")
    }

    @Test
    fun passIsRecordedTheMomentAnAnswerMeetsTheCriterion() = runTest {
        passLevels(3)
        path.skipToLevel(4)
        learnAt(10.0, "r:4", "k:41", "k:42")
        learnAt(10.0, "k:43")
        // k:43 reading is still Apprentice; its meaning is Guru.
        val reading = SrsRepository.cardId("k:43", CardDirection.READING)
        setStability(reading, 2.0)
        assertEquals(3, path.progress().passedLevel)

        clock.advance(2.days)
        srs.review(reading, Rating.GOOD) // grows past Guru → 3 of 3 kanji → level 4 passed now
        assertEquals(4, path.progress().passedLevel, "recorded by the review listener, no screen needed")
        assertEquals(clock.now(), path.progress().passedAt)

        // A lapse right after doesn't take it back.
        srs.review(reading, Rating.AGAIN)
        clock.advance(10.minutes)
        assertEquals(4, path.progress().passedLevel)
        assertEquals(5, path.status().currentLevel)
    }

    @Test
    fun onlyAnExplicitResetLowersTheLevel() = runTest {
        passLevels(4)
        assertEquals(5, path.status().currentLevel)
        path.skipToLevel(2) // skipping never lowers
        assertEquals(5, path.status().currentLevel)

        path.resetToLevel(2)
        assertEquals(1, path.progress().passedLevel)
        // Levels 2–4 are still passed by live stages, so the learner climbs straight back — but level 5+ only
        // through the pass criterion, not through the old record.
        for (l in 2..4) for (k in 1..3) for (card in cards("k:$l$k")) setStability(card, 0.5)
        assertEquals(2, path.status().currentLevel)
        assertFalse(path.lessonQueue().any { it.id == "r:5" }, "reset closes the higher levels")
    }

    @Test
    fun manualUnlocksArePerItemRowsAndLegacyJsonMigratesOnce() = runTest {
        settings.put(SettingsRepository.PATH_MANUAL_UNLOCKS, "[\"k:61\"]")
        path.unlockManually("v:6")
        val queue = path.lessonQueue().map { it.id }
        assertTrue("k:61" in queue && "v:6" in queue, "$queue")
        assertEquals(setOf("k:61", "v:6"), store.unlocks().filter { it.manual }.map { it.itemId }.toSet())
    }
}
