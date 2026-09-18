package app.tsumugi.export

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemKind
import app.tsumugi.exam.AttemptSummary
import app.tsumugi.exam.ExamKind
import app.tsumugi.srs.StageCount
import app.tsumugi.study.Accuracy
import app.tsumugi.study.DayCount
import app.tsumugi.study.KindAccuracy
import app.tsumugi.study.StatsService
import app.tsumugi.study.Streak
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

/** One exam attempt on the trend line. [percent] is the share of points (JLPT) or of correct answers; null for OPI. */
data class ExamTrendPoint(
    val exam: ExamKind,
    val level: String,
    val submittedAt: Instant,
    val percent: Double?,
    val passed: Boolean?,
    val ilr: String?,
    val summary: String,
)

/** A labelled value for the report's tables. */
data class ReportRow(val label: String, val value: String)

/** A titled block of rows; the platform PDF renderer draws sections in order. */
data class ReportSection(val title: String, val rows: List<ReportRow>)

/**
 * The study report (BRIEF_V2 G-10, DECISIONS D-115): one model that iOS (UIGraphicsPDFRenderer) and Android
 * (android.graphics.pdf) render. [sections] is the text content ready to lay out; the typed fields are there for
 * charts (heat-map, exam trend).
 */
data class StudyReport(
    val generatedAt: Instant,
    val periodDays: Int,
    val streak: Streak,
    val reviewsAllTime: Long,
    val reviewsInPeriod: Int,
    val studyDaysInPeriod: Int,
    /** Answer accuracy over the last 30 days, all kinds together. */
    val accuracy: Accuracy,
    val accuracyByKind: List<KindAccuracy>,
    val stages: List<StageCount>,
    val itemsByKind: Map<ItemKind, Long>,
    /** Passed kanji-path level (0 = none yet). */
    val pathLevel: Int,
    val examTrend: List<ExamTrendPoint>,
    /** Reviews per day over the period, oldest first. */
    val daily: List<DayCount>,
) {
    val sections: List<ReportSection>
        get() = listOf(
            ReportSection(
                "Summary",
                listOf(
                    ReportRow("Period", "last $periodDays days (to ${generatedAt.toString().take(10)})"),
                    ReportRow("Current streak", "${streak.current} days (longest ${streak.longest})"),
                    ReportRow("Study days", "$studyDaysInPeriod of $periodDays"),
                    ReportRow("Reviews in period", reviewsInPeriod.toString()),
                    ReportRow("Reviews all time", reviewsAllTime.toString()),
                    ReportRow("Kanji path level passed", if (pathLevel > 0) pathLevel.toString() else "—"),
                    ReportRow("Accuracy (30 days)", pct(accuracy.ratio, accuracy.total)),
                ),
            ),
            ReportSection("Accuracy by kind (30 days)", accuracyByKind.map { ReportRow(it.kind.label, pct(it.accuracy.ratio, it.accuracy.total) + " of ${it.accuracy.total}") }),
            ReportSection("Items by stage", stages.map { ReportRow(it.stage.label, it.count.toString()) }),
            ReportSection("Items by kind", itemsByKind.entries.sortedBy { it.key.ordinal }.map { ReportRow(it.key.label, it.value.toString()) }),
            ReportSection(
                "Exam trend",
                examTrend.map { p ->
                    ReportRow(
                        "${p.submittedAt.toString().take(10)} ${p.exam.name.replace('_', ' ')} ${p.level}".trim(),
                        listOfNotNull(p.percent?.let { "${(it * 100).roundToInt()}%" }, p.passed?.let { if (it) "pass" else "not passed" }, p.ilr?.let { "ILR $it" })
                            .joinToString(" · ").ifEmpty { p.summary },
                    )
                },
            ),
        ).filter { it.rows.isNotEmpty() }

    private fun pct(ratio: Double, total: Int) = if (total == 0) "—" else "${(ratio * 100).roundToInt()}%"
}

/** Builds [StudyReport] from the stats service, the item table, path progress and exam history. Runs on IO. */
class StudyReportBuilder(
    private val db: TsumugiDatabase,
    private val stats: StatsService,
    private val passedLevel: suspend () -> Int,
    /** Exam history, newest first (ExamService.history); empty when exams aren't available. */
    private val examHistory: suspend () -> List<AttemptSummary>,
    private val clock: Clock = Clock.System,
) {
    @Throws(Exception::class)
    suspend fun build(periodDays: Int = 30): StudyReport {
        val days = periodDays.coerceIn(7, 365)
        val snapshot = stats.snapshot(heatmapDays = days)
        val (allTime, byKind) = withContext(Dispatchers.IO) {
            // Answers only (daily_stats leaves out lesson introductions and undone reviews).
            db.srsQueries.statsSlotsSince(Long.MIN_VALUE).executeAsList().sumOf { it.reviews } to
                db.srsQueries.itemCount().executeAsList().mapNotNull { row ->
                    runCatching { ItemKind.valueOf(row.kind) }.getOrNull()?.let { it to row.COUNT }
                }.toMap()
        }
        val accuracy = snapshot.accuracy.values.fold(Accuracy(0, 0)) { a, b -> Accuracy(a.correct + b.correct, a.total + b.total) }
        val trend = examHistory().sortedBy { it.submittedAt }.map { a ->
            val s = a.scoring
            val percent = when {
                s.total != null && s.totalMax != null && s.totalMax > 0 -> s.total.toDouble() / s.totalMax
                s.byType.sumOf { it.total } > 0 && a.exam != ExamKind.OPI -> s.byType.sumOf { it.correct }.toDouble() / s.byType.sumOf { it.total }
                else -> null
            }
            ExamTrendPoint(a.exam, a.level, a.submittedAt, percent, s.passed, s.ilr ?: s.ilrProvisional, a.summary)
        }
        return StudyReport(
            generatedAt = clock.now(),
            periodDays = days,
            streak = snapshot.streak,
            reviewsAllTime = allTime,
            reviewsInPeriod = snapshot.heatmap.sumOf { it.count },
            studyDaysInPeriod = snapshot.heatmap.count { it.count > 0 },
            accuracy = accuracy,
            accuracyByKind = snapshot.accuracyList,
            stages = snapshot.stageList,
            itemsByKind = byKind,
            pathLevel = passedLevel(),
            examTrend = trend,
            daily = snapshot.heatmap,
        )
    }
}

/** One local day of review totals (from `daily_stats`), for the Notion stats push and charts. */
data class DayTotals(val date: kotlinx.datetime.LocalDate, val reviews: Int, val correct: Int, val graded: Int, val elapsedMs: Long) {
    val accuracy: Double? get() = if (graded == 0) null else correct.toDouble() / graded
    val minutes: Int get() = ((elapsedMs + 30_000) / 60_000).toInt()
}

object DailyTotals {
    private const val SLOT_MS = 900_000L

    /** Days with reviews since [sinceMs], oldest first, bucketed into local days of [tz] (quarter-hour slots, D-046). */
    @Throws(Exception::class)
    suspend fun since(db: TsumugiDatabase, sinceMs: Long, tz: kotlinx.datetime.TimeZone): List<DayTotals> = withContext(Dispatchers.IO) {
        val firstSlot = -((-sinceMs).floorDiv(SLOT_MS))
        val out = LinkedHashMap<kotlinx.datetime.LocalDate, DayTotals>()
        for (s in db.srsQueries.statsSlotsSince(firstSlot).executeAsList()) {
            val date = Instant.fromEpochMilliseconds(s.slot * SLOT_MS).toLocalDateTime(tz).date
            val prev = out[date]
            out[date] = DayTotals(
                date, (prev?.reviews ?: 0) + s.reviews.toInt(), (prev?.correct ?: 0) + s.correct.toInt(),
                (prev?.graded ?: 0) + s.graded.toInt(), (prev?.elapsedMs ?: 0) + s.elapsed_ms,
            )
        }
        out.values.sortedBy { it.date }
    }

    /** Consecutive-study-day count ending at each day (for the "Streak" column). */
    fun streaks(days: List<DayTotals>): Map<kotlinx.datetime.LocalDate, Int> {
        val out = HashMap<kotlinx.datetime.LocalDate, Int>()
        var prev: kotlinx.datetime.LocalDate? = null
        var run = 0
        for (d in days.filter { it.reviews > 0 }.sortedBy { it.date }) {
            run = if (prev != null && prev.toEpochDays() + 1 == d.date.toEpochDays()) run + 1 else 1
            out[d.date] = run
            prev = d.date
        }
        return out
    }
}
