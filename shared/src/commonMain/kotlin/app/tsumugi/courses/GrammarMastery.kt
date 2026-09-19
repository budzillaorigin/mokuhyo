package app.tsumugi.courses

import app.tsumugi.db.TsumugiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * The learner's mastery checkbox per grammar point (Chika Sensei's checklist; BRIEF_V2 §6.6, DECISIONS D-231).
 * It's independent of SRS: ticking it never touches a card, and reviews never tick it. Stored in `grammar_mastery`
 * and synced last-writer-wins on the flag. A row is only ever added; unticking writes mastered = 0 with a newer time,
 * so an untick on one device beats an older tick on another.
 */
class GrammarMasteryStore(private val db: TsumugiDatabase, private val clock: Clock = Clock.System) {
    private val q get() = db.coursesQueries

    /** Ids of the points currently ticked. */
    @Throws(Exception::class)
    suspend fun masteredIds(): Set<String> = withContext(Dispatchers.IO) { q.masteredPointIds().executeAsList().toSet() }

    @Throws(Exception::class)
    suspend fun isMastered(pointId: String): Boolean = pointId in masteredIds()

    /** Ticks or unticks [pointId]. Records a sync change only when the flag actually changes. */
    @Throws(Exception::class)
    suspend fun setMastered(pointId: String, mastered: Boolean) = withContext(Dispatchers.IO) {
        val now = clock.now().toEpochMilliseconds()
        val flag = if (mastered) 1L else 0L
        db.transaction {
            q.insertMasteryIfAbsent(pointId, flag, now)
            q.updateMastery(flag, now, pointId)
        }
    }

    /** Ticks or unticks several points at once (e.g. "mark all of module 3"). */
    @Throws(Exception::class)
    suspend fun setMasteredAll(pointIds: List<String>, mastered: Boolean) = withContext(Dispatchers.IO) {
        val now = clock.now().toEpochMilliseconds()
        val flag = if (mastered) 1L else 0L
        db.transaction {
            for (id in pointIds) {
                q.insertMasteryIfAbsent(id, flag, now)
                q.updateMastery(flag, now, id)
            }
        }
    }
}
