package app.tsumugi.study

import app.tsumugi.db.ReviewsSince
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.immersion.ImmersionTargetProgress
import app.tsumugi.l10n.AppLocale
import app.tsumugi.l10n.L10n
import app.tsumugi.l10n.Labels
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
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant

enum class TodayBlockKind { REVIEWS, LESSONS, GRAMMAR, IMMERSION, SHADOWING, SPEAKING, WRITING }

/**
 * What the UI opens for a block (BRIEF_V2 G-01). Each case carries everything the screen needs to start, so the
 * Today screen never re-derives the plan: reviews start with `graph.startReviews(limit)`, a reader document opens by
 * id, a dialogue or scenario by id, the writing canvas with the listed kanji.
 */
sealed interface TodayLaunch {
    /** `AppGraph.startReviews(limit)`: the cap is the day's review budget minus what was answered today. */
    data class Reviews(val limit: Int) : TodayLaunch

    /** `AppGraph.startLessons()` for [count] lessons. */
    data class Lessons(val count: Int) : TodayLaunch

    /** The kana course comes first for absolute beginners (G-13): `AppGraph.kana().lessonQueue(settings, count)`. */
    data class Kana(val count: Int) : TodayLaunch

    /** `AppGraph.grammarLessons()` for [count] points. */
    data class Grammar(val count: Int) : TodayLaunch

    /** Open [target] in the reader (document) or the listening player (dialogue). */
    data class Immersion(val target: ImmersionCandidate) : TodayLaunch

    /**
     * Record and compare each sentence: play it (system TTS or the audio pack), record the learner, then
     * `PronunciationService.analyze(sentence.japanese, transcript, pcm)` and, with the reference audio,
     * `PronunciationService.shadowing(reference, attempt)`.
     */
    data class Shadowing(val sentences: List<ShadowingSentence>) : TodayLaunch

    /** `AppGraph.roleplay(scenarioId)`. */
    data class Speaking(val scenarioId: String, val title: String, val aiGenerated: Boolean) : TodayLaunch

    /** The writing canvas (`WritingService.guided` / `checkRaw`) for each kanji. */
    data class Writing(val kanji: List<String>) : TodayLaunch
}

/**
 * One step of the day, in order. [count] items, about [minutes] minutes. [launch] is null when there is nothing to
 * start (the block is done, or an honest empty state explains what's missing in [detail]). Optional blocks can be
 * skipped without spoiling the day. A Pomodoro timer ([FocusTimer], [TodayPlan.timerOptions]) can wrap any block.
 */
data class TodayBlock(
    val kind: TodayBlockKind,
    val title: String,
    val detail: String,
    val count: Int,
    val minutes: Int,
    val done: Boolean,
    val launch: TodayLaunch? = null,
    val optional: Boolean = false,
)

/** NativShark-style phase: a band of path levels with different block weights (BRIEF §5.6). */
enum class LearningPhase(val label: String, val levels: IntRange) {
    FOUNDATIONS("Foundations", 1..5), CORE("Core", 6..20), INTERMEDIATE("Intermediate", 21..40), ADVANCED("Advanced", 41..60);

    companion object {
        fun forLevel(level: Int) = entries.firstOrNull { level in it.levels } ?: ADVANCED
    }
}

/** What a weekly challenge counts. Block challenges count finished Today blocks (`today_block_done`). */
enum class ChallengeKind { STUDY_DAYS, REVIEWS, NEW_ITEMS, BLOCKS, IMMERSION_DAYS, SPEAKING_BLOCKS }

data class WeeklyChallenge(val title: String, val progress: Int, val goal: Int, val kind: ChallengeKind = ChallengeKind.STUDY_DAYS) {
    val complete: Boolean get() = progress >= goal
}

data class TodayPlan(
    val date: LocalDate,
    val budgetMinutes: Int,
    val phase: LearningPhase,
    val blocks: List<TodayBlock>,
    val plannedMinutes: Int,
    val challenge: WeeklyChallenge,
) {
    /** The review cap for today's session: pass to `AppGraph.startReviews(limit)`. 0 when the budget is used up. */
    val reviewLimit: Int get() = (blocks.firstOrNull { it.kind == TodayBlockKind.REVIEWS }?.launch as? TodayLaunch.Reviews)?.limit ?: 0

    /** Pomodoro lengths (minutes) the UI offers from any block (BRIEF §5.6). */
    val timerOptions: List<Int> get() = FocusTimer.OPTIONS_MINUTES

    fun block(kind: TodayBlockKind): TodayBlock? = blocks.firstOrNull { it.kind == kind }
}

/**
 * The Today screen's plan (BRIEF §5.6, BRIEF_V2 G-01): reviews first (capped by the daily budget), then new lessons
 * whose count adapts to the budget, yesterday's accuracy and the review backlog, grammar, and — by budget — the
 * immersion, shadowing, speaking and writing blocks built from [TodayCandidates]:
 *
 * | budget | blocks |
 * |---|---|
 * | 10 min | reviews, lessons, grammar |
 * | 20 min | + immersion (5 min), shadowing (3 sentences) |
 * | 40 min | + speaking moment (optional); immersion 8 min, shadowing 4 |
 * | 60 min | + writing (3 kanji, optional); immersion 10 min, shadowing 5 |
 *
 * Finished blocks are recorded in `today_block_done` ([markDone]; reviews, lessons and grammar are recorded as soon
 * as the plan sees them finished), which is what the block-based weekly challenges count (G-11).
 */
class TodayPlanner(
    private val db: TsumugiDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    /** How immersion candidates are matched to the learner; the §6.4 difficulty score plugs in here (Phase 11). */
    var difficulty: ImmersionDifficulty = SimpleImmersionDifficulty

    /**
     * Minutes of immersion logged today against the daily target (BRIEF_V2 §6.11); null = no log wired. Meeting the
     * target marks the immersion block done, so the immersion challenge counts logged immersion too.
     */
    var immersionProgress: (suspend () -> ImmersionTargetProgress)? = null

    @Throws(Exception::class)
    suspend fun plan(
        dueReviews: Int,
        path: PathStatus?,
        grammarAvailable: Int,
        candidates: TodayCandidates = TodayCandidates(),
        locale: AppLocale = L10n.locale,
        /** Kana lessons still to take when the kana course is needed (G-13); they replace path lessons until done. */
        kanaLessons: Int = 0,
    ): TodayPlan = withContext(Dispatchers.IO) {
        val tz = zone()
        val now = clock.now()
        val today = now.toLocalDateTime(tz).date
        val budget = settings.int(SettingsRepository.DAILY_BUDGET_MINUTES, DEFAULT_BUDGET)
        val phase = LearningPhase.forLevel(path?.currentLevel ?: 1)
        fun t(key: String, vararg args: Any) = Labels.text(key, locale, *args)

        val todayStart = today.atStartOfDayIn(tz).toEpochMilliseconds()
        val yesterdayStart = today.minus(DatePeriod(days = 1)).atStartOfDayIn(tz).toEpochMilliseconds()
        val recent = db.srsQueries.reviewsSince(yesterdayStart).executeAsList()
        // One lesson per item, not per card (an item introduces 2 cards; a ghost card's introduction is no lesson).
        val introducedToday = recent.filter { it.ts >= todayStart && it.isLesson() }
        val lessonsToday = introducedToday.filter { it.item_kind != ItemKind.GRAMMAR.name }.distinctItems()
        val grammarToday = introducedToday.filter { it.item_kind == ItemKind.GRAMMAR.name }.distinctItems()
        val answeredToday = recent.count { it.ts >= todayStart && it.rating in 1L..4L }
        val yesterday = recent.filter { it.ts in yesterdayStart until todayStart && it.correct != null }
        val yesterdayAccuracy = if (yesterday.isEmpty()) null else yesterday.count { it.correct == 1L }.toDouble() / yesterday.size
        val doneRows = db.studyQueries.blocksDoneOn(today.toString()).executeAsList().toSet()
        val immersionMet = immersionProgress?.invoke()?.met == true
        fun recorded(kind: TodayBlockKind) = kind.name in doneRows || (kind == TodayBlockKind.IMMERSION && immersionMet)

        val blocks = ArrayList<TodayBlock>()

        // Reviews: the cap is the budget's review share; answers already given today count against it.
        val reviewCap = reviewCap(budget, phase)
        val capLeft = (reviewCap - answeredToday).coerceAtLeast(0)
        val reviews = minOf(dueReviews, capLeft)
        val reviewsDone = (dueReviews == 0 && answeredToday > 0) || (capLeft == 0 && answeredToday > 0) || recorded(TodayBlockKind.REVIEWS)
        blocks += TodayBlock(
            TodayBlockKind.REVIEWS, Labels.block(TodayBlockKind.REVIEWS, locale),
            when {
                capLeft == 0 && dueReviews > 0 -> t("today.reviews.capReached", answeredToday)
                dueReviews > capLeft -> t("today.reviews.capped", reviews, dueReviews)
                else -> t("today.reviews.due", dueReviews)
            },
            reviews, minutes(reviews / REVIEWS_PER_MINUTE), done = reviewsDone,
            launch = if (reviews > 0) TodayLaunch.Reviews(reviews) else null,
        )

        val lessonTarget = lessonTarget(budget, yesterdayAccuracy, dueReviews)
        if (kanaLessons > 0) {
            // Foundations start with kana; path lessons follow once the course is done.
            val kana = minOf(kanaLessons, (lessonTarget / KANA_LESSON_SIZE).coerceAtLeast(1))
            blocks += TodayBlock(
                TodayBlockKind.LESSONS, Labels.block(TodayBlockKind.LESSONS, locale), t("today.kana.count", kana),
                kana, minutes(kana * KANA_LESSON_SIZE * MINUTES_PER_LESSON), done = recorded(TodayBlockKind.LESSONS),
                launch = TodayLaunch.Kana(kana),
            )
        }
        val lessons = if (path == null || kanaLessons > 0) 0 else minOf(path.availableLessons, (lessonTarget - lessonsToday).coerceAtLeast(0))
        if (path != null && kanaLessons == 0) {
            blocks += TodayBlock(
                TodayBlockKind.LESSONS, Labels.block(TodayBlockKind.LESSONS, locale),
                when {
                    lessons > 0 -> t("today.lessons.count", lessons, path.currentLevel)
                    lessonsToday > 0 -> t("today.lessons.doneToday", lessonsToday)
                    path.availableLessons == 0 -> t("today.lessons.locked")
                    else -> t("today.lessons.paused")
                },
                lessons, minutes(lessons * MINUTES_PER_LESSON), done = (lessons == 0 && lessonsToday > 0) || recorded(TodayBlockKind.LESSONS),
                launch = if (lessons > 0) TodayLaunch.Lessons(lessons) else null,
            )
        }

        val grammarTarget = (budget / 20).coerceIn(1, 3)
        val grammar = minOf(grammarAvailable, (grammarTarget - grammarToday).coerceAtLeast(0))
        if (grammarAvailable > 0 || grammarToday > 0) {
            blocks += TodayBlock(
                TodayBlockKind.GRAMMAR, Labels.block(TodayBlockKind.GRAMMAR, locale),
                if (grammar > 0) t("today.grammar.count", grammar) else t("today.grammar.doneToday", grammarToday),
                grammar, minutes(grammar * MINUTES_PER_GRAMMAR), done = (grammar == 0 && grammarToday > 0) || recorded(TodayBlockKind.GRAMMAR),
                launch = if (grammar > 0) TodayLaunch.Grammar(grammar) else null,
            )
        }

        blocks += extraBlocks(budget, today, candidates, locale, ::recorded)

        // Blocks the plan sees finished become facts, so block challenges count them on every device.
        for (b in blocks) if (b.done && !recorded(b.kind) && b.kind in DERIVED_DONE) markDoneBlocking(b.kind, today)
        if (immersionMet && TodayBlockKind.IMMERSION.name !in doneRows) markDoneBlocking(TodayBlockKind.IMMERSION, today)

        TodayPlan(today, budget, phase, blocks, blocks.filterNot { it.done || it.optional }.sumOf { it.minutes }, challenge(today, tz, budget, locale))
    }

    /** Records that the learner finished [kind] today (call when a block's screen completes). Idempotent. */
    @Throws(Exception::class)
    suspend fun markDone(kind: TodayBlockKind) = withContext(Dispatchers.IO) {
        markDoneBlocking(kind, clock.now().toLocalDateTime(zone()).date)
    }

    private fun markDoneBlocking(kind: TodayBlockKind, day: LocalDate) {
        db.studyQueries.markBlockDone(day.toString(), kind.name, clock.now().toEpochMilliseconds())
    }

    /** The day's review budget: its share of the minutes at [REVIEWS_PER_MINUTE], at least 20. */
    internal fun reviewCap(budget: Int, phase: LearningPhase): Int =
        (budget * REVIEWS_PER_MINUTE * phase.reviewShare).roundToInt().coerceAtLeast(20)

    private fun extraBlocks(
        budget: Int,
        today: LocalDate,
        c: TodayCandidates,
        locale: AppLocale,
        recorded: (TodayBlockKind) -> Boolean,
    ): List<TodayBlock> {
        val out = ArrayList<TodayBlock>()
        val seed = today.toEpochDays().toLong()
        if (budget >= 20) {
            val pick = c.immersion.filterNot { it.finished }
                .minWithOrNull(compareBy<ImmersionCandidate> { difficulty.mismatch(it, c.learnerJlpt) }.thenBy { rotate(it.id, seed) })
            val minutes = when {
                budget >= 60 -> 10
                budget >= 40 -> 8
                else -> 5
            }
            val done = recorded(TodayBlockKind.IMMERSION)
            out += TodayBlock(
                TodayBlockKind.IMMERSION, Labels.block(TodayBlockKind.IMMERSION, locale),
                pick?.let { Labels.text("today.immersion.detail", locale, it.title, it.levelLabel ?: "N${c.learnerJlpt}") }
                    ?: Labels.text("today.immersion.none", locale),
                if (pick == null) 0 else 1, if (pick == null) 0 else minutes, done = done,
                launch = pick?.takeUnless { done }?.let { TodayLaunch.Immersion(it) },
            )

            val sentenceCount = when {
                budget >= 60 -> 5
                budget >= 40 -> 4
                else -> 3
            }
            val sentences = c.shadowing.distinctBy { it.japanese }.take(sentenceCount)
            if (sentences.size >= MIN_SHADOWING) {
                val shadowDone = recorded(TodayBlockKind.SHADOWING)
                out += TodayBlock(
                    TodayBlockKind.SHADOWING, Labels.block(TodayBlockKind.SHADOWING, locale),
                    Labels.text("today.shadowing.count", locale, sentences.size),
                    sentences.size, sentences.size, done = shadowDone,
                    launch = if (shadowDone) null else TodayLaunch.Shadowing(sentences),
                )
            }
        }
        if (budget >= 40) {
            val atLevel = c.scenarios.filter { it.jlpt == c.learnerJlpt }.ifEmpty { c.scenarios.filter { it.jlpt >= c.learnerJlpt } }.ifEmpty { c.scenarios }
            atLevel.minByOrNull { rotate(it.id, seed) }?.let { s ->
                val done = recorded(TodayBlockKind.SPEAKING)
                out += TodayBlock(
                    TodayBlockKind.SPEAKING, Labels.block(TodayBlockKind.SPEAKING, locale), Labels.text("today.speaking.detail", locale, s.title),
                    1, SPEAKING_MINUTES, done = done, optional = true,
                    launch = if (done) null else TodayLaunch.Speaking(s.id, s.title, s.aiGenerated),
                )
            }
        }
        if (budget >= 60 && c.writingKanji.isNotEmpty()) {
            val kanji = c.writingKanji.distinct().sortedBy { rotate(it, seed) }.take(WRITING_KANJI)
            val done = recorded(TodayBlockKind.WRITING)
            out += TodayBlock(
                TodayBlockKind.WRITING, Labels.block(TodayBlockKind.WRITING, locale),
                Labels.text("today.writing.detail", locale, kanji.joinToString(if (locale == AppLocale.JA) "・" else ", ")),
                kanji.size, kanji.size, done = done, optional = true,
                launch = if (done) null else TodayLaunch.Writing(kanji),
            )
        }
        return out
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

    /**
     * A small locally generated weekly goal, rotating by ISO week (BRIEF §5.12 weekly challenges). Half of them are
     * tied to real Today blocks the learner finishes (G-11).
     */
    private fun challenge(today: LocalDate, tz: TimeZone, budget: Int, locale: AppLocale): WeeklyChallenge {
        var monday = today
        while (monday.dayOfWeek != DayOfWeek.MONDAY) monday = monday.minus(DatePeriod(days = 1))
        val since = monday.atStartOfDayIn(tz).toEpochMilliseconds()
        val weekIndex = (monday.toEpochDays() / 7).toInt()
        fun reviews() = db.srsQueries.reviewsSince(since).executeAsList()
        fun blocks() = db.studyQueries.blocksDoneBetween(monday.toString(), monday.plus(DatePeriod(days = 6)).toString()).executeAsList()
        fun challenge(kind: ChallengeKind, key: String, goal: Int, progress: Int) =
            WeeklyChallenge(Labels.text(key, locale, goal), progress, goal, kind)
        return when (weekIndex % 6) {
            0 -> challenge(ChallengeKind.STUDY_DAYS, "challenge.studyDays", 5, reviews().filter { it.rating in 1L..4L }
                .map { Instant.fromEpochMilliseconds(it.ts).toLocalDateTime(tz).date }.distinct().size)
            1 -> challenge(ChallengeKind.REVIEWS, "challenge.reviews", 300, reviews().count { it.rating in 1L..4L })
            2 -> challenge(ChallengeKind.BLOCKS, "challenge.blocks", blockGoal(budget), blocks().size)
            3 -> challenge(ChallengeKind.IMMERSION_DAYS, "challenge.immersion", 4, blocks().count { it.block == TodayBlockKind.IMMERSION.name })
            4 -> challenge(
                ChallengeKind.SPEAKING_BLOCKS, "challenge.speaking", 3,
                blocks().count { it.block == TodayBlockKind.SPEAKING.name || it.block == TodayBlockKind.SHADOWING.name },
            )
            else -> challenge(ChallengeKind.NEW_ITEMS, "challenge.newItems", 25, reviews().filter { it.isLesson() }.distinctItems())
        }
    }

    /** About four days' worth of the blocks this budget plans. */
    private fun blockGoal(budget: Int) = when {
        budget <= 10 -> 8
        budget <= 20 -> 12
        budget <= 40 -> 16
        else -> 20
    }

    private fun ReviewsSince.isLesson() =
        rating == SrsRepository.INTRODUCED.toLong() && card_direction != CardDirection.GHOST.name

    private fun List<ReviewsSince>.distinctItems() = map { it.item_id }.distinct().size

    private fun minutes(value: Number) = kotlin.math.ceil(value.toDouble()).toInt()

    /** A stable per-day order, so the same candidates rotate from day to day. */
    private fun rotate(id: String, seed: Long): Int = (id.hashCode().toLong() * 31 + seed * 7919).hashCode()

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
        const val MIN_SHADOWING = 3
        const val SPEAKING_MINUTES = 5
        const val WRITING_KANJI = 3
        /** Kana per kana lesson (a lesson is one row of the table, ~5 characters). */
        const val KANA_LESSON_SIZE = 5
        private val DERIVED_DONE = setOf(TodayBlockKind.REVIEWS, TodayBlockKind.LESSONS, TodayBlockKind.GRAMMAR)
    }
}
