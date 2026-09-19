package app.tsumugi.sync

import app.tsumugi.audio.PitchTestItem
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.pitch.PitchTestService
import app.tsumugi.srs.SrsRepository
import app.tsumugi.study.games.GameKind
import app.tsumugi.study.games.GameResult
import app.tsumugi.study.games.GameScores
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

/** BRIEF_V2 §6.7, §6.9: pitch-test answers and game scores are facts merged by union (D-284, D-286). */
class Phase13SyncTest {
    private val clock = TestClock()
    private val server = FakeSyncServer()
    private val items = listOf(
        PitchTestItem("p1", 1, "箸", "はし", 2, 1, "atamadaka", "はし", "chopsticks", "はしが"),
        PitchTestItem("p2", 2, "橋", "はし", 2, 2, "odaka", "はし", "bridge", "はしが"),
        PitchTestItem("p3", 3, "端", "はし", 2, 0, "heiban", "はし", "edge", "はしが"),
    )

    private inner class Device(val id: String) {
        val driver = inMemoryDriver(TsumugiDatabase.Schema)
        val db = TsumugiDatabase(driver)
        val srs = SrsRepository(db, id, clock)
        val engine = SyncEngine(driver, db, srs, id, server, null, clock)
        val pitch = PitchTestService(db, id, items, clock = clock)
        val games = GameScores(db, id, clock) { TimeZone.UTC }
    }

    @Test
    fun pitchAnswersAndGameScoresMergeByUnion() = runTest {
        val a = Device("device-a")
        val b = Device("device-b")
        val sa = a.pitch.start(random = Random(1))
        repeat(3) { sa.answer(assertNotNull(sa.next()).expected, 900); clock.advance(1.seconds) }
        val sb = b.pitch.start(random = Random(2))
        val q = assertNotNull(sb.next())
        sb.answer(q.options.first { it.id != q.expected }.id, 1500)
        a.games.record(GameResult(GameKind.REFLEX, 300, 18, 20, 8, 60_000))
        b.games.record(GameResult(GameKind.ATOM, 120, 5, 7, 2, 90_000))

        a.engine.sync(); b.engine.sync(); a.engine.sync()

        for (d in listOf(a, b)) {
            val stats = d.pitch.stats()
            assertEquals(4, stats.total.attempts, d.id)
            assertEquals(3, stats.total.correct, d.id)
            assertEquals(420, d.games.weekPoints(), d.id)
        }
        // Re-syncing never duplicates a fact.
        b.engine.sync(); a.engine.sync()
        assertEquals(4, a.pitch.stats().total.attempts)
        assertEquals(2, b.db.gamesQueries.gameScoresBetween("0000", "9999").executeAsList().size)
    }
}
