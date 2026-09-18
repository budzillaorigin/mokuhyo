package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.StageCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

data class Streak(val current: Int, val longest: Int, val studiedToday: Boolean, val onVacation: Boolean)

data class DayCount(val date: LocalDate, val count: Int)

data class Accuracy(val correct: Int, val total: Int) {
    val ratio: Double get() = if (total == 0) 0.0 else correct.toDouble() / total
}

data class StatsSnapshot(
    val streak: Streak,
    val reviewsToday: Int,
    /** Reviews per day, oldest first (includes zero days), for the heat-map. */
    val heatmap: List<DayCount>,
    /** Answer accuracy per item kind over the last [ACCURACY_WINDOW_DAYS] days. */
    val accuracy: Map<ItemKind, Accuracy>,
    val stages: Map<Stage, Int>,
    /** Reviews coming due per day for the next week (today first). */
    val forecast: List<DayCount>,
) {
    /** List forms of the maps above, in a fixed order (easier to render, and to use from Swift). */
    val stageList: List<StageCount> get() = Stage.entries.map { StageCount(it, stages[it] ?: 0) }
    val accuracyList: List<KindAccuracy> get() = accuracy.map { (k, a) -> KindAccuracy(k, a) }.sortedBy { it.kind.ordinal }
}

data class KindAccuracy(val kind: ItemKind, val accuracy: Accuracy)

/**
 * Progress numbers for the Me tab and Today screen (BRIEF §5.12). Everything is derived from the review log,
 * so it is identical on every synced device. A day counts for the streak with at least one answer
 * (lesson introductions don't count); vacation mode freezes the streak without breaking it.
 */
class StatsService(
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    suspend fun snapshot(heatmapDays: Int = 365): StatsSnapshot = withContext(Dispatchers.IO) {
        val tz = zone()
        val now = clock.now()
        val today = now.toLocalDateTime(tz).date
        val since = now - heatmapDays.days
        val rows = db.srsQueries.reviewsSince(since.toEpochMilliseconds()).executeAsList()
            .filter { it.rating != SrsRepository.INTRODUCED.toLong() }

        val perDay = rows.groupingBy { Instant.fromEpochMilliseconds(it.ts).toLocalDateTime(tz).date }.eachCount()
        val heatmap = (heatmapDays - 1 downTo 0).map { d -> today.minus(DatePeriod(days = d)).let { DayCount(it, perDay[it] ?: 0) } }

        val accuracySince = (now - ACCURACY_WINDOW_DAYS.days).toEpochMilliseconds()
        val accuracy = rows.filter { it.ts >= accuracySince && it.correct != null }
            .groupBy { ItemKind.valueOf(it.item_kind) }
            .mapValues { (_, r) -> Accuracy(r.count { it.correct == 1L }, r.size) }

        val stages = srs.stages().values.groupingBy { it }.eachCount()

        val tomorrowStart = today.plus(DatePeriod(days = 1)).atStartOfDayIn(tz)
        val forecastEnd = today.plus(DatePeriod(days = FORECAST_DAYS)).atStartOfDayIn(tz)
        val due = db.srsQueries.forecast(0, forecastEnd.toEpochMilliseconds()).executeAsList()
        val forecast = (0 until FORECAST_DAYS).map { d ->
            val date = today.plus(DatePeriod(days = d))
            val count = if (d == 0) {
                due.count { it < tomorrowStart.toEpochMilliseconds() }
            } else {
                due.count { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date == date }
            }
            DayCount(date, count)
        }

        StatsSnapshot(streak(tz, today), perDay[today] ?: 0, heatmap, accuracy, stages, forecast)
    }

    suspend fun streak(): Streak = withContext(Dispatchers.IO) {
        val tz = zone()
        streak(tz, clock.now().toLocalDateTime(tz).date)
    }

    suspend fun setVacation(on: Boolean) {
        settings.put(SettingsRepository.VACATION_SINCE, if (on) clock.now().toEpochMilliseconds().toString() else "")
    }

    private suspend fun streak(tz: TimeZone, today: LocalDate): Streak {
        val days = db.srsQueries.reviewsSince(0).executeAsList()
            .filter { it.rating != SrsRepository.INTRODUCED.toLong() }
            .map { Instant.fromEpochMilliseconds(it.ts).toLocalDateTime(tz).date }
            .distinct()
            .sorted()
            .toSet()
        val vacationSince = settings.get(SettingsRepository.VACATION_SINCE)?.toLongOrNull()
            ?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date }
        fun counts(d: LocalDate) = d in days || (vacationSince != null && d >= vacationSince)

        val studiedToday = today in days
        var cursor = if (studiedToday || (vacationSince != null && today >= vacationSince)) today else today.minus(DatePeriod(days = 1))
        var current = 0
        while (counts(cursor)) {
            current++
            cursor = cursor.minus(DatePeriod(days = 1))
        }

        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && prev.plus(DatePeriod(days = 1)) == d) run + 1 else 1
            longest = maxOf(longest, run)
            prev = d
        }
        return Streak(current, maxOf(longest, current), studiedToday, vacationSince != null)
    }

    companion object {
        const val ACCURACY_WINDOW_DAYS = 30
        const val FORECAST_DAYS = 7
    }
}
