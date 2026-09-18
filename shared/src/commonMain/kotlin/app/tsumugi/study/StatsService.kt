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

data class Streak(
    val current: Int,
    val longest: Int,
    val studiedToday: Boolean,
    val onVacation: Boolean,
    /** Today is a freeze day (spent freeze or vacation): the streak is safe without studying. */
    val frozenToday: Boolean = false,
    /** Freezes left this calendar month ([StatsService.FREEZES_PER_MONTH] minus the ones spent). */
    val freezesLeft: Int = 0,
)

/** Why [StatsService.freeze] did or didn't freeze a day. */
enum class FreezeResult { FROZEN, ALREADY_FROZEN, ALREADY_STUDIED, NO_FREEZES_LEFT, OUT_OF_RANGE }

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
 * (lesson introductions don't count). A freeze day (BRIEF_V2 G-11, DECISIONS D-106) neither breaks nor extends the
 * streak: a spent freeze from the monthly allowance, or a day of vacation mode. Vacation no longer counts its days
 * as study days; while it is on, each day is a freeze, and turning it off writes those days to `streak_freeze`.
 *
 * Day totals come from `daily_stats` (quarter-hour buckets kept by triggers on every review write, undo and
 * tombstone, BRIEF_V2 F-27), so neither the heat-map nor the streak scans the review log; only the 30-day accuracy
 * window reads reviews, through the review_ts index.
 */
class StatsService(
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    @Throws(Exception::class)
    suspend fun snapshot(heatmapDays: Int = 365): StatsSnapshot = withContext(Dispatchers.IO) {
        val tz = zone()
        val now = clock.now()
        val today = now.toLocalDateTime(tz).date
        val since = now - heatmapDays.days
        val perDay = reviewsPerDay(since.toEpochMilliseconds(), tz)
        val heatmap = (heatmapDays - 1 downTo 0).map { d -> today.minus(DatePeriod(days = d)).let { DayCount(it, perDay[it] ?: 0) } }

        val accuracySince = (now - ACCURACY_WINDOW_DAYS.days).toEpochMilliseconds()
        val accuracy = db.srsQueries.reviewsSince(accuracySince).executeAsList()
            .filter { it.rating != SrsRepository.INTRODUCED.toLong() && it.correct != null }
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

    @Throws(Exception::class)
    suspend fun streak(): Streak = withContext(Dispatchers.IO) {
        val tz = zone()
        streak(tz, clock.now().toLocalDateTime(tz).date)
    }

    /**
     * Vacation mode: every day while it is on is a freeze day. Turning it off records those days (start through
     * today) as VACATION freezes, so they stay freezes on every device after the setting changes.
     */
    @Throws(Exception::class)
    suspend fun setVacation(on: Boolean) {
        if (!on) {
            val tz = zone()
            val since = vacationSince(tz)
            if (since != null) {
                val today = clock.now().toLocalDateTime(tz).date
                withContext(Dispatchers.IO) {
                    db.transaction {
                        var d: LocalDate = since
                        while (d <= today) {
                            db.studyQueries.insertFreeze(d.toString(), REASON_VACATION, clock.now().toEpochMilliseconds())
                            d = d.plus(DatePeriod(days = 1))
                        }
                    }
                }
            }
        }
        settings.put(SettingsRepository.VACATION_SINCE, if (on) clock.now().toEpochMilliseconds().toString() else "")
    }

    /**
     * Spends a freeze on [day] (default today): allowed for yesterday (to bridge a missed day) through
     * [FREEZE_AHEAD_DAYS] ahead, on a day with no study, while the month's allowance lasts.
     */
    @Throws(Exception::class)
    suspend fun freeze(day: LocalDate? = null): FreezeResult = withContext(Dispatchers.IO) {
        val tz = zone()
        val today = clock.now().toLocalDateTime(tz).date
        val target = day ?: today
        if (target < today.minus(DatePeriod(days = 1)) || target > today.plus(DatePeriod(days = FREEZE_AHEAD_DAYS))) {
            return@withContext FreezeResult.OUT_OF_RANGE
        }
        val frozen = db.studyQueries.allFreezes().executeAsList()
        if (frozen.any { it.day == target.toString() }) return@withContext FreezeResult.ALREADY_FROZEN
        if (target <= today && target in reviewsPerDay(target.atStartOfDayIn(tz).toEpochMilliseconds(), tz).keys) {
            return@withContext FreezeResult.ALREADY_STUDIED
        }
        if (freezesLeft(frozen.map { it.day to it.reason }, target) <= 0) return@withContext FreezeResult.NO_FREEZES_LEFT
        db.studyQueries.insertFreeze(target.toString(), REASON_FREEZE, clock.now().toEpochMilliseconds())
        FreezeResult.FROZEN
    }

    /** Freeze days, oldest first (spent freezes and finished vacations; not the current vacation). */
    @Throws(Exception::class)
    suspend fun freezeDays(): List<LocalDate> = withContext(Dispatchers.IO) {
        db.studyQueries.allFreezes().executeAsList().map { LocalDate.parse(it.day) }
    }

    private fun freezesLeft(frozen: List<Pair<String, String>>, inMonthOf: LocalDate): Int {
        val prefix = inMonthOf.toString().substring(0, 7) // "2026-09"
        return FREEZES_PER_MONTH - frozen.count { (day, reason) -> reason == REASON_FREEZE && day.startsWith(prefix) }
    }

    private suspend fun vacationSince(tz: TimeZone): LocalDate? =
        settings.get(SettingsRepository.VACATION_SINCE)?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date }

    /**
     * Answers per local day, from the first whole slot at or after [sinceMs] (callers only read days that start
     * after it). Slots are UTC quarter-hours; every real zone offset is a multiple of 15 minutes, so a slot never
     * straddles two local days.
     */
    private fun reviewsPerDay(sinceMs: Long, tz: TimeZone): Map<LocalDate, Int> {
        val firstSlot = -((-sinceMs).floorDiv(SLOT_MS))
        val out = HashMap<LocalDate, Int>()
        for (slot in db.srsQueries.statsSlotsSince(firstSlot).executeAsList()) {
            val date = slotDate(slot.slot, tz)
            out[date] = (out[date] ?: 0) + slot.reviews.toInt()
        }
        return out.filterValues { it > 0 }
    }

    private fun slotDate(slot: Long, tz: TimeZone): LocalDate = Instant.fromEpochMilliseconds(slot * SLOT_MS).toLocalDateTime(tz).date

    private suspend fun streak(tz: TimeZone, today: LocalDate): Streak {
        val days = reviewsPerDay(0, tz).keys
        val vacationSince = vacationSince(tz)
        val rows = db.studyQueries.allFreezes().executeAsList()
        val frozen = HashSet<LocalDate>(rows.map { LocalDate.parse(it.day) })
        if (vacationSince != null) {
            var d: LocalDate = vacationSince
            while (d <= today) {
                frozen += d
                d = d.plus(DatePeriod(days = 1))
            }
        }
        val result = streakOf(days, frozen, today)
        return Streak(
            result.first, result.second, today in days, vacationSince != null,
            frozenToday = today in frozen, freezesLeft = freezesLeft(rows.map { it.day to it.reason }, today).coerceAtLeast(0),
        )
    }

    companion object {
        const val ACCURACY_WINDOW_DAYS = 30
        const val FORECAST_DAYS = 7
        /** Freezes the learner can spend per calendar month (vacation days don't use them). */
        const val FREEZES_PER_MONTH = 2
        const val FREEZE_AHEAD_DAYS = 30
        const val REASON_FREEZE = "FREEZE"
        const val REASON_VACATION = "VACATION"
        /** daily_stats bucket width (see srs.sq). */
        private const val SLOT_MS = 900_000L

        /**
         * (current, longest) streak over [studied] days with [frozen] days that neither break nor extend a run. Today
         * never breaks the current streak (the day isn't over); a studied day always extends it, frozen or not.
         */
        internal fun streakOf(studied: Set<LocalDate>, frozen: Set<LocalDate>, today: LocalDate): Pair<Int, Int> {
            val first = (studied + frozen).minOrNull() ?: return 0 to 0
            var current = 0
            var cursor = if (today in studied) today else today.minus(DatePeriod(days = 1))
            while (cursor >= first) {
                when (cursor) {
                    in studied -> current++
                    in frozen -> Unit
                    else -> break
                }
                cursor = cursor.minus(DatePeriod(days = 1))
            }
            var longest = 0
            var run = 0
            var d = first
            val last = maxOf(today, studied.maxOrNull() ?: today)
            while (d <= last) {
                when (d) {
                    in studied -> run++
                    in frozen -> Unit
                    else -> if (d != today) run = 0
                }
                longest = maxOf(longest, run)
                d = d.plus(DatePeriod(days = 1))
            }
            return current to maxOf(longest, current)
        }
    }
}
