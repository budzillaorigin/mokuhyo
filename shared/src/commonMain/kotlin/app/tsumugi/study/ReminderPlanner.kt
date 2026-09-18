package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

data class Reminder(val at: Instant, val title: String, val body: String, val dueCount: Int)

/**
 * Decides when to nudge the learner about reviews (BRIEF §5.12: reviews-due reminders with quiet hours).
 * Platforms schedule the single [Reminder] this returns as a local notification and re-plan whenever the app
 * goes to the background, so no background work or server push is needed.
 */
class ReminderPlanner(
    private val db: TsumugiDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    @Throws(Exception::class)
    suspend fun next(): Reminder? = withContext(Dispatchers.IO) {
        if (!settings.bool(SettingsRepository.REMINDERS_ENABLED, true)) return@withContext null
        val threshold = settings.int(SettingsRepository.REMINDER_THRESHOLD, DEFAULT_THRESHOLD)
        val quietStart = settings.int(SettingsRepository.QUIET_START_MINUTE, DEFAULT_QUIET_START)
        val quietEnd = settings.int(SettingsRepository.QUIET_END_MINUTE, DEFAULT_QUIET_END)

        val now = clock.now()
        val horizon = now + HORIZON_DAYS.days
        val dues = db.srsQueries.forecast(0, horizon.toEpochMilliseconds()).executeAsList().sorted()
        if (dues.isEmpty()) return@withContext null
        // The moment enough reviews have piled up (at least a little in the future, so it isn't instant).
        val index = (minOf(threshold, dues.size) - 1).coerceAtLeast(0)
        var at = maxOf(Instant.fromEpochMilliseconds(dues[index]), now + MIN_DELAY_MINUTES.minutes)
        at = outsideQuietHours(at, quietStart, quietEnd)
        val count = dues.count { it <= at.toEpochMilliseconds() }
        Reminder(at, "Reviews are ready", "$count review${if (count == 1) "" else "s"} waiting. A few minutes keeps your streak going.", count)
    }

    /** Moves [at] to the end of quiet hours if it falls inside them (quiet hours may span midnight). */
    internal fun outsideQuietHours(at: Instant, quietStart: Int, quietEnd: Int): Instant {
        if (quietStart == quietEnd) return at
        val tz = zone()
        val local = at.toLocalDateTime(tz)
        val minute = local.hour * 60 + local.minute
        val inQuiet = if (quietStart < quietEnd) minute in quietStart until quietEnd else minute >= quietStart || minute < quietEnd
        if (!inQuiet) return at
        val endTime = LocalTime(quietEnd / 60, quietEnd % 60)
        val endDate = if (quietStart > quietEnd && minute >= quietStart) local.date.plus(DatePeriod(days = 1)) else local.date
        return endDate.atTime(endTime).toInstant(tz)
    }

    companion object {
        const val DEFAULT_THRESHOLD = 10
        /** Quiet hours default 22:00–08:00 (minutes after midnight, local time). */
        const val DEFAULT_QUIET_START = 22 * 60
        const val DEFAULT_QUIET_END = 8 * 60
        const val HORIZON_DAYS = 7
        const val MIN_DELAY_MINUTES = 30
    }
}
