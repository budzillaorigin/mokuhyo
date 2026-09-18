package app.tsumugi.settings

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.platform.Secrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Synced user settings (last-writer-wins by timestamp) with typed accessors. */
class SettingsRepository(private val db: TsumugiDatabase, private val clock: Clock = Clock.System) {
    private val q get() = db.userQueries

    @Throws(Exception::class)
    suspend fun get(key: String): String? = withContext(Dispatchers.IO) { q.settingValue(key).executeAsOneOrNull() }

    @Throws(Exception::class)
    suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) {
        q.putSetting(key, value, clock.now().toEpochMilliseconds())
    }

    @Throws(Exception::class)
    suspend fun int(key: String, default: Int): Int = get(key)?.toIntOrNull() ?: default
    @Throws(Exception::class)
    suspend fun bool(key: String, default: Boolean): Boolean = get(key)?.toBooleanStrictOrNull() ?: default

    @Throws(Exception::class)
    suspend fun lessonBatchSize(): Int = int(LESSON_BATCH_SIZE, DEFAULT_LESSON_BATCH)
    @Throws(Exception::class)
    suspend fun setLessonBatchSize(size: Int) = put(LESSON_BATCH_SIZE, size.coerceIn(1, 50).toString())

    /** Target recall probability for FSRS (0.7–0.99). Takes effect for the next scheduled review. */
    @Throws(Exception::class)
    suspend fun desiredRetention(): Double = get(DESIRED_RETENTION)?.toDoubleOrNull() ?: DEFAULT_RETENTION
    @Throws(Exception::class)
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
        /** Add a writing card (draw the kanji) to new kanji lessons. */
        const val WRITING_CARDS = "writing.cardsEnabled"
        /** Remind once at least this many reviews are due. */
        const val REMINDER_THRESHOLD = "notifications.threshold"
        /** Quiet hours, minutes after local midnight. */
        const val QUIET_START_MINUTE = "notifications.quietStart"
        const val QUIET_END_MINUTE = "notifications.quietEnd"
    }
}

/**
 * Per-device configuration that never syncs (CLAUDE.md rule 16, BRIEF_V2 F-31, DECISIONS D-047): AI engine and
 * model choice, endpoint URLs, audio engine — anything tied to this device's hardware or network. Stored in the
 * `device_setting` table, which has no sync trigger. Same accessors as [SettingsRepository], so a service can
 * switch stores without other changes.
 *
 * Keys stored here (docs/DECISIONS.md D-047): [KEYS]. The v1 → v2 migration (1.sqm) moved their old values out of
 * the synced `setting` table once.
 */
class DeviceSettings(private val db: TsumugiDatabase) {
    private val q get() = db.userQueries

    @Throws(Exception::class)
    suspend fun get(key: String): String? = withContext(Dispatchers.IO) { q.deviceSetting(key).executeAsOneOrNull() }

    @Throws(Exception::class)
    suspend fun put(key: String, value: String) = withContext(Dispatchers.IO) { q.putDeviceSetting(key, value) }

    @Throws(Exception::class)
    suspend fun remove(key: String) = withContext(Dispatchers.IO) { q.removeDeviceSetting(key) }

    @Throws(Exception::class)
    suspend fun int(key: String, default: Int): Int = get(key)?.toIntOrNull() ?: default
    @Throws(Exception::class)
    suspend fun bool(key: String, default: Boolean): Boolean = get(key)?.toBooleanStrictOrNull() ?: default

    companion object {
        /** Every key that lives per device (AiService's engine/model/endpoint/audio settings). Keep 1.sqm in step. */
        val KEYS: List<String> = listOf(
            "ai.llm", "ai.local_model", "ai.endpoint_url", "ai.endpoint_model", "ai.stt", "ai.local_stt_model",
            "ai.stt_endpoint_url", "ai.tts", "ai.voicevox_url", "ai.voicevox_speaker",
        )
    }
}

/**
 * Device-local state that never syncs.
 *
 * The user database is included in OS backups and device transfers, so a restored copy would carry the old
 * phone's device id. Two devices sharing one id breaks sync (last-writer-wins tie-breaks and change tracking are
 * per device). The id is therefore mirrored in [secrets] (Keychain `ThisDeviceOnly` / Android Keystore file
 * excluded from backup), which never moves to another device. A stored id with no matching secret means this
 * database came from somewhere else, and a fresh id is issued (DECISIONS D-039).
 */
class DeviceState(private val db: TsumugiDatabase, private val secrets: Secrets? = null) {
    private val q get() = db.metaQueries

    /** Stable random id of this install; tags reviews so sync can tell devices apart. */
    val deviceId: String by lazy {
        val stored = q.get(DEVICE_ID).executeAsOneOrNull()
        val claimed = secrets?.get(SECRET_KEY)
        when {
            stored != null && (secrets == null || claimed == stored) -> stored
            else -> Uuid.random().toString().also {
                q.put(DEVICE_ID, it)
                secrets?.put(SECRET_KEY, it)
            }
        }
    }

    fun get(key: String): String? = q.get(key).executeAsOneOrNull()
    fun put(key: String, value: String) = q.put(key, value)

    private companion object {
        const val DEVICE_ID = "device_id"
        const val SECRET_KEY = "device.id"
    }
}
