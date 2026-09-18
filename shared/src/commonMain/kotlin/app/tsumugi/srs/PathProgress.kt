package app.tsumugi.srs

import app.tsumugi.db.TsumugiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/** The persisted path level (CLAUDE.md rule 11). [passedLevel] 0 = nothing passed yet. */
data class PathProgress(val passedLevel: Int, val passedAt: Instant?, val generation: Int)

/** A lesson unlock that is a persisted fact. [manual] = unlocked by hand; otherwise its prerequisites reached Guru once. */
data class PathUnlock(val itemId: String, val manual: Boolean)

/**
 * Persisted path progress and unlocks (BRIEF_V2 F-04, DECISIONS D-041): the passed level only rises
 * ([recordPassed]) unless the learner confirms an explicit [resetTo]; unlocks are insert-only. Both sync —
 * progress with the MAX rule, unlocks by set union — so devices converge on the higher level and no unlock is lost.
 */
class PathProgressStore(private val db: TsumugiDatabase, private val clock: Clock = Clock.System) {
    private val q get() = db.userQueries

    @Throws(Exception::class)
    suspend fun progress(track: String = TRACK): PathProgress = io {
        q.pathProgress(track).executeAsOneOrNull()
            ?.let { PathProgress(it.passed_level.toInt(), it.passed_at.takeIf { at -> at > 0 }?.let(Instant::fromEpochMilliseconds), it.generation.toInt()) }
            ?: PathProgress(0, null, 0)
    }

    /** Records [level] as passed if it is higher than the stored level. Returns true when it rose. */
    @Throws(Exception::class)
    suspend fun recordPassed(level: Int, track: String = TRACK): Boolean = io {
        if (level < 1) return@io false
        db.transactionWithResult {
            val before = q.pathProgress(track).executeAsOneOrNull()?.passed_level ?: 0L
            if (before >= level) return@transactionWithResult false
            q.insertPathProgressIfAbsent(track)
            q.raisePathProgress(level.toLong(), clock.now().toEpochMilliseconds(), track)
            true
        }
    }

    /**
     * Explicit "reset to level N" (the UI confirms first): the learner restarts at [level], i.e. level − 1 counts as
     * passed. Bumps the reset generation, so this lower level wins over every level any device recorded before it.
     */
    @Throws(Exception::class)
    suspend fun resetTo(level: Int, track: String = TRACK) = io {
        db.transaction {
            q.insertPathProgressIfAbsent(track)
            q.resetPathProgress((level - 1).coerceAtLeast(0).toLong(), clock.now().toEpochMilliseconds(), track)
        }
    }

    @Throws(Exception::class)
    suspend fun unlocks(): List<PathUnlock> = io { q.allUnlocks().executeAsList().map { PathUnlock(it.item_id, it.source == MANUAL) } }

    @Throws(Exception::class)
    suspend fun addUnlocks(itemIds: Collection<String>, manual: Boolean) = io {
        if (itemIds.isEmpty()) return@io
        val now = clock.now().toEpochMilliseconds()
        db.transaction { itemIds.forEach { q.insertUnlock(it, if (manual) MANUAL else AUTO, now) } }
    }

    /**
     * One-time move of the v1 manual-unlock JSON array (a synced setting) into per-item rows. Runs once per
     * device (flag in app_meta); rows from several devices merge by union, so repeating it elsewhere is harmless.
     */
    @Throws(Exception::class)
    suspend fun importLegacyManualUnlocks(json: String?) = io {
        if (db.metaQueries.get(LEGACY_UNLOCKS_MIGRATED).executeAsOneOrNull() != null) return@io
        val ids = json?.let { runCatching { Json.decodeFromString<List<String>>(it) }.getOrNull() }.orEmpty()
        val now = clock.now().toEpochMilliseconds()
        db.transaction {
            ids.forEach { q.insertUnlock(it, MANUAL, now) }
            db.metaQueries.put(LEGACY_UNLOCKS_MIGRATED, "1")
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val TRACK = "kanji-path"
        const val MANUAL = "manual"
        const val AUTO = "auto"
        private const val LEGACY_UNLOCKS_MIGRATED = "migrated.pathManualUnlocks"
    }
}
