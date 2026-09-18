package app.tsumugi.settings

import app.tsumugi.db.TsumugiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Synced user settings (last-writer-wins by timestamp) with typed accessors. */
class SettingsRepository(private val db: TsumugiDatabase, private val clock: Clock = Clock.System) {
    private val q get() = db.userQueries

    suspend fun get(key: String): String? = withContext(Dispatchers.IO) { q.settingValue(key).executeAsOneOrNull() }

    suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        q.putSetting(key, value, clock.now().toEpochMilliseconds())
    }

    suspend fun int(key: String, default: Int): Int = get(key)?.toIntOrNull() ?: default
    suspend fun bool(key: String, default: Boolean): Boolean = get(key)?.toBooleanStrictOrNull() ?: default

    suspend fun lessonBatchSize(): Int = int(LESSON_BATCH_SIZE, DEFAULT_LESSON_BATCH)
    suspend fun setLessonBatchSize(size: Int) = put(LESSON_BATCH_SIZE, size.coerceIn(1, 50).toString())

    /** Target recall probability for FSRS (0.7–0.99). Takes effect for the next scheduled review. */
    suspend fun desiredRetention(): Double = get(DESIRED_RETENTION)?.toDoubleOrNull() ?: DEFAULT_RETENTION
    suspend fun setDesiredRetention(value: Double) = put(DESIRED_RETENTION, value.coerceIn(0.7, 0.99).toString())

    companion object Keys {
        const val DEFAULT_LESSON_BATCH = 5
        const val DEFAULT_RETENTION = 0.9

        /** Lowest path level the learner is on (manual level skip / known-kanji import). */
        const val PATH_LEVEL_FLOOR = "path.levelFloor"
        /** JSON array of path item ids unlocked by hand. */
        const val PATH_MANUAL_UNLOCKS = "path.manualUnlocks"
        const val LESSON_BATCH_SIZE = "lessons.batchSize"
        const val DAILY_BUDGET_MINUTES = "today.budgetMinutes"
        const val DESIRED_RETENTION = "srs.desiredRetention"
        /** JSON array of 21 FSRS weights fitted by the optimizer; absent = defaults. */
        const val FSRS_WEIGHTS = "srs.fsrsWeights"
        /** Epoch-ms start of vacation mode; absent = off. Streaks don't break while on vacation. */
        const val VACATION_SINCE = "streak.vacationSince"
        const val REMINDERS_ENABLED = "notifications.enabled"
        /** Remind once at least this many reviews are due. */
        const val REMINDER_THRESHOLD = "notifications.threshold"
        /** Quiet hours, minutes after local midnight. */
        const val QUIET_START_MINUTE = "notifications.quietStart"
        const val QUIET_END_MINUTE = "notifications.quietEnd"
    }
}

/** Device-local state that never syncs. */
class DeviceState(private val db: TsumugiDatabase) {
    private val q get() = db.metaQueries

    /** Stable random id of this install; tags reviews so sync can tell devices apart. */
    val deviceId: String by lazy {
        q.get(DEVICE_ID).executeAsOneOrNull() ?: Uuid.random().toString().also { q.put(DEVICE_ID, it) }
    }

    fun get(key: String): String? = q.get(key).executeAsOneOrNull()
    fun put(key: String, value: String) = q.put(key, value)

    private companion object {
        const val DEVICE_ID = "device_id"
    }
}
