package app.tsumugi.immersion

import app.tsumugi.db.Immersion_session
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.settings.SettingsRepository
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
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Where a stretch of immersion happened (BRIEF_V2 §6.11). */
enum class ImmersionOrigin { READER, MEDIA, PODCAST, DIALOGUE, MANUAL }

/** Active: attention on the Japanese (reading, watching with focus). Passive: background listening. */
enum class ImmersionMode { ACTIVE, PASSIVE }

data class ImmersionSession(
    val id: String,
    /** Local ISO date the session started on. */
    val day: String,
    val source: ImmersionOrigin,
    val mode: ImmersionMode,
    val startedAt: Long,
    val durationSeconds: Long,
    val ref: String?,
    val title: String?,
    val deviceId: String,
) {
    val minutes: Double get() = durationSeconds / 60.0
}

/** Minutes of one source on one day, for the per-source breakdown. */
data class SourceMinutes(val source: ImmersionOrigin, val minutes: Int)

/** One day of the immersion log. Minutes are rounded down from the day's summed seconds. */
data class ImmersionDay(
    val date: LocalDate,
    val activeMinutes: Int,
    val passiveMinutes: Int,
    /** Per source, in [ImmersionOrigin] order, only sources with time. */
    val bySource: List<SourceMinutes>,
    val targetMinutes: Int,
) {
    val totalMinutes: Int get() = activeMinutes + passiveMinutes
    val targetMet: Boolean get() = targetMinutes > 0 && totalMinutes >= targetMinutes
}

/** Today's minutes against the daily target (the Today plan's immersion hook). */
data class ImmersionTargetProgress(val minutesToday: Int, val targetMinutes: Int) {
    val met: Boolean get() = targetMinutes > 0 && minutesToday >= targetMinutes
    val fraction: Double get() = if (targetMinutes <= 0) 0.0 else (minutesToday.toDouble() / targetMinutes).coerceAtMost(1.0)
}

/** A running stretch the platform started with [ImmersionLog.start]; pass it back to [ImmersionLog.stop]. */
data class ImmersionTicket(
    val id: String,
    val source: ImmersionOrigin,
    val mode: ImmersionMode,
    val ref: String?,
    val title: String?,
    val startedAt: Long,
)

/**
 * The immersion log (BRIEF_V2 §6.11, DECISIONS D-167). Active and passive minutes per day, by source, with a daily
 * target the Today plan and the heat-map read.
 *
 * - Automatic logging: the reader and the media player call [start] when the learner opens a text or presses play
 *   and [stop] when they leave or pause (a ticket per stretch), or [report] with an elapsed time they measured.
 * - Manual entry ([addManual]) for immersion away from the app.
 * - Rows are written once and sync by union; [delete] is a tombstone that reaches every device.
 *
 * Stretches shorter than [MIN_SECONDS] are dropped (a text opened by mistake), and one stretch is capped at
 * [MAX_SECONDS] (a player left running overnight).
 */
@OptIn(ExperimentalAtomicApi::class)
class ImmersionLog(
    private val db: TsumugiDatabase,
    private val deviceId: String,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val q get() = db.immersionQueries
    private val running = AtomicReference(emptyMap<String, ImmersionTicket>())

    /** Starts timing a stretch (not stored until [stop]). Cheap; call it from the UI thread. */
    fun start(source: ImmersionOrigin, mode: ImmersionMode, ref: String? = null, title: String? = null): ImmersionTicket {
        val ticket = ImmersionTicket(Uuid.random().toString(), source, mode, ref, title, clock.now().toEpochMilliseconds())
        update { it + (ticket.id to ticket) }
        return ticket
    }

    /** Tickets started and not stopped yet (e.g. to stop them all when the app goes to the background). */
    val runningTickets: List<ImmersionTicket> get() = running.load().values.sortedBy { it.startedAt }

    /** Stops [ticket] and logs the elapsed wall-clock time. Null when it was too short or already stopped. */
    @Throws(Exception::class)
    suspend fun stop(ticket: ImmersionTicket): ImmersionSession? {
        var known = false
        update { m -> known = ticket.id in m; m - ticket.id }
        if (!known) return null
        val elapsed = (clock.now().toEpochMilliseconds() - ticket.startedAt) / 1000
        return write(ticket.id, ticket.source, ticket.mode, ticket.startedAt, elapsed, ticket.ref, ticket.title)
    }

    /** Stops every running ticket (app going to the background). */
    @Throws(Exception::class)
    suspend fun stopAll(): List<ImmersionSession> = runningTickets.mapNotNull { stop(it) }

    /** Logs a stretch the platform timed itself, ending now. */
    @Throws(Exception::class)
    suspend fun report(
        source: ImmersionOrigin,
        mode: ImmersionMode,
        elapsedSeconds: Long,
        ref: String? = null,
        title: String? = null,
    ): ImmersionSession? {
        val end = clock.now().toEpochMilliseconds()
        val seconds = elapsedSeconds.coerceIn(0, MAX_SECONDS)
        return write(Uuid.random().toString(), source, mode, end - seconds * 1000, seconds, ref, title)
    }

    /**
     * A manual entry: [minutes] of immersion on [date] (today or earlier). [source] defaults to MANUAL; the learner
     * may say it was a podcast or a show watched elsewhere.
     */
    @Throws(Exception::class)
    suspend fun addManual(
        date: LocalDate,
        minutes: Int,
        mode: ImmersionMode = ImmersionMode.ACTIVE,
        source: ImmersionOrigin = ImmersionOrigin.MANUAL,
        title: String? = null,
    ): ImmersionSession {
        require(minutes in 1..MAX_SECONDS / 60) { "minutes must be between 1 and ${MAX_SECONDS / 60}" }
        val tz = zone()
        require(date <= clock.now().toLocalDateTime(tz).date) { "a manual entry can't be in the future" }
        // Noon of that day: inside the day in any time zone the learner travels to.
        val at = date.atStartOfDayIn(tz).toEpochMilliseconds() + 12 * 3_600_000L
        return write(Uuid.random().toString(), source, mode, at, minutes * 60L, null, title?.trim()?.ifEmpty { null }, day = date)!!
    }

    /** Tombstones a logged session (it disappears on every device). */
    @Throws(Exception::class)
    suspend fun delete(id: String) {
        io { q.tombstoneImmersion(clock.now().toEpochMilliseconds(), id) }
    }

    @Throws(Exception::class)
    suspend fun sessions(from: LocalDate, to: LocalDate): List<ImmersionSession> =
        io { q.immersionBetween(from.toString(), to.toString()).executeAsList().map { it.toSession() } }

    /** The last [days] days, oldest first, zero days included (heat-map rows). */
    @Throws(Exception::class)
    suspend fun days(days: Int = 30): List<ImmersionDay> {
        val target = targetMinutes()
        return io { dayRows(db, zone(), clock, days, target) }
    }

    @Throws(Exception::class)
    suspend fun today(): ImmersionDay = days(1).single()

    /** Hours logged, all time (the roadmap's immersion measure). */
    @Throws(Exception::class)
    suspend fun totalHours(): Double = io { q.immersionTotalSeconds().executeAsOne() / 3600.0 }

    /** The daily immersion target in minutes (synced learner preference; 0 = no target). */
    @Throws(Exception::class)
    suspend fun targetMinutes(): Int = settings.int(TARGET_KEY, DEFAULT_TARGET_MINUTES).coerceIn(0, MAX_TARGET)

    @Throws(Exception::class)
    suspend fun setTargetMinutes(minutes: Int) = settings.put(TARGET_KEY, minutes.coerceIn(0, MAX_TARGET).toString())

    @Throws(Exception::class)
    suspend fun targetProgress(): ImmersionTargetProgress = today().let { ImmersionTargetProgress(it.totalMinutes, it.targetMinutes) }

    private suspend fun write(
        id: String,
        source: ImmersionOrigin,
        mode: ImmersionMode,
        startedAt: Long,
        seconds: Long,
        ref: String?,
        title: String?,
        day: LocalDate? = null,
    ): ImmersionSession? {
        if (seconds < MIN_SECONDS) return null
        val capped = seconds.coerceAtMost(MAX_SECONDS)
        val date = day ?: Instant.fromEpochMilliseconds(startedAt).toLocalDateTime(zone()).date
        val now = clock.now().toEpochMilliseconds()
        return io {
            q.insertImmersion(id, date.toString(), source.name, mode.name, startedAt, capped, ref, title, deviceId, now)
            q.immersionById(id).executeAsOne().toSession()
        }
    }

    private inline fun update(change: (Map<String, ImmersionTicket>) -> Map<String, ImmersionTicket>) {
        while (true) {
            val old = running.load()
            if (running.compareAndSet(old, change(old))) return
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Synced setting: the daily immersion target in minutes. */
        const val TARGET_KEY = "immersion.dailyMinutes"
        const val DEFAULT_TARGET_MINUTES = 20
        const val MAX_TARGET = 600
        const val MIN_SECONDS = 15L
        val MAX_SECONDS: Long = 6.hours.inWholeSeconds

        /** Day rows for the last [days] days (also used by [app.tsumugi.study.StatsService.immersionHeatmap]). */
        internal fun dayRows(db: TsumugiDatabase, tz: TimeZone, clock: Clock, days: Int, target: Int): List<ImmersionDay> {
            val today = clock.now().toLocalDateTime(tz).date
            val first = today.minus(DatePeriod(days = (days - 1).coerceAtLeast(0)))
            val rows = db.immersionQueries.immersionBetween(first.toString(), today.toString()).executeAsList().groupBy { it.day }
            return (0 until days.coerceAtLeast(1)).map { d ->
                val date = first.plus(DatePeriod(days = d))
                val list = rows[date.toString()].orEmpty()
                val active = list.filter { it.mode == ImmersionMode.ACTIVE.name }.sumOf { it.duration_s }
                val passive = list.filter { it.mode != ImmersionMode.ACTIVE.name }.sumOf { it.duration_s }
                val bySource = ImmersionOrigin.entries.mapNotNull { s ->
                    val secs = list.filter { it.source == s.name }.sumOf { it.duration_s }
                    if (secs > 0) SourceMinutes(s, (secs / 60).toInt()) else null
                }
                ImmersionDay(date, (active / 60).toInt(), (passive / 60).toInt(), bySource, target)
            }
        }
    }
}

internal fun Immersion_session.toSession() = ImmersionSession(
    id, day, runCatching { ImmersionOrigin.valueOf(source) }.getOrDefault(ImmersionOrigin.MANUAL),
    runCatching { ImmersionMode.valueOf(mode) }.getOrDefault(ImmersionMode.ACTIVE), started_at, duration_s, ref, title, device_id,
)
