package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemKind
import app.tsumugi.settings.SettingsRepository
import app.tsumugi.srs.PathStatus
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

enum class TodayBlockKind { REVIEWS, LESSONS, GRAMMAR, IMMERSION, SHADOWING, SPEAKING, WRITING }

/** One step of the day, in order. [count] items, about [minutes] minutes. */
data class TodayBlock(val kind: TodayBlockKind, val title: String, val detail: String, val count: Int, val minutes: Int, val done: Boolean)

/** NativShark-style phase: a band of path levels with different block weights (BRIEF §5.6). */
enum class LearningPhase(val label: String, val levels: IntRange) {
    FOUNDATIONS("Foundations", 1..5), CORE("Core", 6..20), INTERMEDIATE("Intermediate", 21..40), ADVANCED("Advanced", 41..60);

    companion object {
        fun forLevel(level: Int) = entries.firstOrNull { level in it.levels } ?: ADVANCED
    }
}

data class WeeklyChallenge(val title: String, val progress: Int, val goal: Int) {
    val complete: Boolean get() = progress >= goal
}

data class TodayPlan(
    val date: LocalDate,
    val budgetMinutes: Int,
    val phase: LearningPhase,
    val blocks: List<TodayBlock>,
    val plannedMinutes: Int,
    val challenge: WeeklyChallenge,
)

/**
 * The Today screen's plan (BRIEF §5.6): reviews first, then new lessons whose count adapts to the daily budget,
 * yesterday's accuracy and the review backlog, then grammar. Features that aren't built yet contribute no blocks
 * (they're added here as they land: immersion, shadowing, speaking, writing).
 */
class TodayPlanner(
    private val db: TsumugiDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    @Throws(Exception::class)
    suspend fun plan(
        dueReviews: Int,
        path: PathStatus?,
        grammarAvailable: Int,
        extraBlocks: List<TodayBlock> = emptyList(),
    ): TodayPlan = withContext(Dispatchers.IO) {
        val tz = zone()
        val now = clock.now()
        val today = now.toLocalDateTime(tz).date
        val budget = settings.int(SettingsRepository.DAILY_BUDGET_MINUTES, DEFAULT_BUDGET)
        val phase = LearningPhase.forLevel(path?.currentLevel ?: 1)

        val todayStart = today.atStartOfDayIn(tz).toEpochMilliseconds()
        val yesterdayStart = today.minus(DatePeriod(days = 1)).atStartOfDayIn(tz).toEpochMilliseconds()
        val recent = db.srsQueries.reviewsSince(yesterdayStart).executeAsList()
        val lessonsToday = recent.count { it.ts >= todayStart && it.rating == SrsRepository.INTRODUCED.toLong() && it.item_kind != ItemKind.GRAMMAR.name }
        val grammarToday = recent.count { it.ts >= todayStart && it.rating == SrsRepository.INTRODUCED.toLong() && it.item_kind == ItemKind.GRAMMAR.name }
        val answeredToday = recent.count { it.ts >= todayStart && it.rating in 1L..4L }
        val yesterday = recent.filter { it.ts in yesterdayStart until todayStart && it.correct != null }
        val yesterdayAccuracy = if (yesterday.isEmpty()) null else yesterday.count { it.correct == 1L }.toDouble() / yesterday.size

        val blocks = ArrayList<TodayBlock>()
        val reviewCap = (budget * REVIEWS_PER_MINUTE * phase.reviewShare).roundToInt().coerceAtLeast(20)
        val reviews = minOf(dueReviews, reviewCap)
        blocks += TodayBlock(
            TodayBlockKind.REVIEWS, "Reviews", if (dueReviews > reviewCap) "$reviews of $dueReviews due (the rest tomorrow)" else "$dueReviews due",
            reviews, minutes(reviews / REVIEWS_PER_MINUTE), done = dueReviews == 0 && answeredToday > 0,
        )

        val lessonTarget = lessonTarget(budget, yesterdayAccuracy, dueReviews)
        val lessons = if (path == null) 0 else minOf(path.availableLessons, (lessonTarget - lessonsToday).coerceAtLeast(0))
        if (path != null) {
            blocks += TodayBlock(
                TodayBlockKind.LESSONS, "New kanji & vocabulary",
                when {
                    lessons > 0 -> "$lessons lessons (level ${path.currentLevel})"
                    lessonsToday > 0 -> "$lessonsToday done today"
                    path.availableLessons == 0 -> "Nothing unlocked yet — reviews unlock more"
                    else -> "Paused while reviews catch up"
                },
                lessons, minutes(lessons * MINUTES_PER_LESSON), done = lessons == 0 && lessonsToday > 0,
            )
        }

        val grammarTarget = (budget / 20).coerceIn(1, 3)
        val grammar = minOf(grammarAvailable, (grammarTarget - grammarToday).coerceAtLeast(0))
        if (grammarAvailable > 0 || grammarToday > 0) {
            blocks += TodayBlock(
                TodayBlockKind.GRAMMAR, "Grammar", if (grammar > 0) "$grammar new points" else "$grammarToday learned today",
                grammar, minutes(grammar * MINUTES_PER_GRAMMAR), done = grammar == 0 && grammarToday > 0,
            )
        }
        blocks += extraBlocks

        TodayPlan(today, budget, phase, blocks, blocks.filterNot { it.done }.sumOf { it.minutes }, challenge(today, tz))
    }

    /** New lessons per day: by budget, eased after a rough day and paused when the backlog is large. */
    internal fun lessonTarget(budget: Int, yesterdayAccuracy: Double?, dueReviews: Int): Int {
        val base = when {
            budget <= 10 -> 3
            budget <= 20 -> 6
            budget <= 40 -> 10
            else -> 15
        }
        val accuracy = when {
            yesterdayAccuracy == null -> 1.0
            yesterdayAccuracy < 0.75 -> 0.5
            yesterdayAccuracy < 0.85 -> 0.8
            else -> 1.0
        }
        val backlog = when {
            dueReviews > 150 -> 0.0
            dueReviews > 80 -> 0.5
            else -> 1.0
        }
        return (base * accuracy * backlog).roundToInt()
    }

    /** A small locally generated weekly goal, rotating by ISO week (BRIEF §5.12 weekly challenges). */
    private fun challenge(today: LocalDate, tz: TimeZone): WeeklyChallenge {
        var monday = today
        while (monday.dayOfWeek != DayOfWeek.MONDAY) monday = monday.minus(DatePeriod(days = 1))
        val since = monday.atStartOfDayIn(tz).toEpochMilliseconds()
        val week = db.srsQueries.reviewsSince(since).executeAsList()
        val weekIndex = (monday.toEpochDays() / 7).toInt()
        return when (weekIndex % 3) {
            0 -> WeeklyChallenge("Study on 5 days this week", week.filter { it.rating in 1L..4L }
                .map { Instant.fromEpochMilliseconds(it.ts).toLocalDateTime(tz).date }.distinct().size, 5)
            1 -> WeeklyChallenge("Answer 300 reviews this week", week.count { it.rating in 1L..4L }, 300)
            else -> WeeklyChallenge("Learn 25 new items this week", week.count { it.rating == SrsRepository.INTRODUCED.toLong() }, 25)
        }
    }

    private fun minutes(value: Number) = kotlin.math.ceil(value.toDouble()).toInt()

    private val LearningPhase.reviewShare: Double
        get() = when (this) {
            LearningPhase.FOUNDATIONS -> 0.5
            LearningPhase.CORE -> 0.6
            LearningPhase.INTERMEDIATE -> 0.55
            LearningPhase.ADVANCED -> 0.5
        }

    companion object {
        const val DEFAULT_BUDGET = 20
        val BUDGET_OPTIONS = listOf(10, 20, 40, 60)
        const val REVIEWS_PER_MINUTE = 6.0
        const val MINUTES_PER_LESSON = 1.5
        const val MINUTES_PER_GRAMMAR = 4.0
    }
}
