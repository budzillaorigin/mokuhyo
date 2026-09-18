package app.tsumugi.study

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.Stage
import app.tsumugi.grammar.GrammarService
import app.tsumugi.practice.PracticeRepository
import app.tsumugi.reader.ReaderDocumentSummary
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

enum class ImmersionSource { READER, DIALOGUE }

/**
 * Something to read or listen to in the immersion block: a reader document or a practice-pack dialogue.
 * [jlpt] 5..1 (0 = above N1, null = unknown); [knownRatio] is the share of known words when the reader analyzed it.
 */
data class ImmersionCandidate(
    val source: ImmersionSource,
    val id: String,
    val title: String,
    val jlpt: Int?,
    val knownRatio: Double? = null,
    val levelLabel: String? = null,
    /** Read to the end already: not picked again. */
    val finished: Boolean = false,
    val aiGenerated: Boolean = false,
)

/** A sentence to shadow, from one of today's grammar points. */
data class ShadowingSentence(
    val japanese: String,
    val english: String,
    val pointId: String,
    val pointTitle: String,
    val aiGenerated: Boolean,
)

data class ScenarioCandidate(val id: String, val title: String, val jlpt: Int, val aiGenerated: Boolean)

/** The material the extra Today blocks choose from ([TodayCandidateSource] collects it; tests build it by hand). */
data class TodayCandidates(
    /** The learner's JLPT level, 5..1 ([LearnerLevel.jlptForPathLevel]). */
    val learnerJlpt: Int = 5,
    val immersion: List<ImmersionCandidate> = emptyList(),
    val shadowing: List<ShadowingSentence> = emptyList(),
    val scenarios: List<ScenarioCandidate> = emptyList(),
    /** Kanji the learner knows (Guru or better); empty when writing practice isn't available (no KanjiVG). */
    val writingKanji: List<String> = emptyList(),
)

/**
 * How far an immersion candidate is from the learner's level; lower is a better fit. The hook for the full §6.4
 * difficulty score (BRIEF_V2 Phase 11), which replaces [SimpleImmersionDifficulty] without touching the planner.
 */
fun interface ImmersionDifficulty {
    fun mismatch(candidate: ImmersionCandidate, learnerJlpt: Int): Double
}

/**
 * The simple score used until §6.4 (DECISIONS D-100): the JLPT distance, with text above the learner's level
 * counting 1.5× (i+1 is fine, i+3 is not), unknown levels treated as two levels off, and a known-word ratio of 90%+
 * earning a half-level discount (comprehensible input).
 */
object SimpleImmersionDifficulty : ImmersionDifficulty {
    override fun mismatch(candidate: ImmersionCandidate, learnerJlpt: Int): Double {
        val level = candidate.jlpt ?: return 2.0
        val diff = learnerJlpt - level // positive: harder than the learner (N5 learner, N3 text → 2)
        var score = if (diff > 0) diff * 1.5 else abs(diff).toDouble()
        if ((candidate.knownRatio ?: 0.0) >= 0.9) score -= 0.5
        return score.coerceAtLeast(0.0)
    }
}

object LearnerLevel {
    /** The JLPT level (5..1) the learner's kanji-path level corresponds to (WaniKani-style bands). */
    fun jlptForPathLevel(level: Int): Int = when {
        level <= 10 -> 5
        level <= 20 -> 4
        level <= 35 -> 3
        level <= 50 -> 2
        else -> 1
    }
}

/**
 * Collects [TodayCandidates] from the installed packs and the learner's data (AppGraph wires it). Every source is
 * optional: a missing pack only removes that block.
 */
class TodayCandidateSource(
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val readerDocuments: suspend () -> List<ReaderDocumentSummary>,
    private val practice: suspend () -> PracticeRepository?,
    private val grammar: suspend () -> GrammarService?,
    private val writingAvailable: suspend () -> Boolean,
    private val clock: Clock = Clock.System,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    @Throws(Exception::class)
    suspend fun collect(pathLevel: Int?): TodayCandidates {
        val jlpt = LearnerLevel.jlptForPathLevel(pathLevel ?: 1)
        val practice = runCatching { practice() }.getOrNull()
        val reader = runCatching { readerDocuments() }.getOrDefault(emptyList()).map {
            ImmersionCandidate(
                ImmersionSource.READER, it.id, it.title, it.jlptEstimate, it.knownRatio, it.levelLabel,
                finished = it.length > 0 && it.progress >= it.length * 95 / 100,
            )
        }
        val dialogues = practice?.dialogues().orEmpty().map {
            ImmersionCandidate(ImmersionSource.DIALOGUE, it.id, it.title, it.jlpt, levelLabel = "N${it.jlpt}", aiGenerated = it.isAiGenerated)
        }
        val scenarios = practice?.scenarios().orEmpty().map { ScenarioCandidate(it.id, it.titleEn, it.jlpt, it.isAiGenerated) }
        val kanji = if (runCatching { writingAvailable() }.getOrDefault(false)) {
            srs.stages().filter { (id, stage) -> id.startsWith("k:") && stage >= Stage.GURU }.keys.map { it.removePrefix("k:") }
        } else {
            emptyList()
        }
        return TodayCandidates(jlpt, reader + dialogues, shadowing(), scenarios, kanji)
    }

    /**
     * 3–5 example sentences from today's grammar: points introduced today, else the next lesson points, else the
     * grammar points reviewed most recently.
     */
    private suspend fun shadowing(): List<ShadowingSentence> {
        val g = runCatching { grammar() }.getOrNull() ?: return emptyList()
        val tz = zone()
        val todayStart = clock.now().toLocalDateTime(tz).date.atStartOfDayIn(tz).toEpochMilliseconds()
        val recent = withContext(Dispatchers.IO) {
            db.srsQueries.reviewsSince(todayStart - RECENT_DAYS_MS).executeAsList()
                .filter { it.item_kind == ItemKind.GRAMMAR.name && it.card_direction != CardDirection.GHOST.name }
        }
        val introducedToday = recent.filter { it.ts >= todayStart && it.rating == SrsRepository.INTRODUCED.toLong() }
            .map { it.item_id.removePrefix("g:") }.distinct()
        val pointIds = introducedToday.ifEmpty { g.lessonQueue(3).map { it.id } }
            .ifEmpty { recent.sortedByDescending { it.ts }.map { it.item_id.removePrefix("g:") }.distinct().take(3) }
        val out = ArrayList<ShadowingSentence>()
        for (id in pointIds) {
            val detail = g.point(id) ?: continue
            detail.examples.take(2).forEach { ex ->
                out += ShadowingSentence(ex.japanese, ex.english, id, detail.point.title, ex.isAiGenerated)
            }
        }
        return out.take(5)
    }

    private companion object {
        const val RECENT_DAYS_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * A PomoSpeak-style timed session that can wrap any Today block (BRIEF §5.6: 5/10/15/20 minutes "available from any
 * block"): a work period, then a short break. The speaking block can use the activity-queue [activities.PomodoroSession]
 * instead; this is the plain timer for the rest.
 */
class FocusTimer(
    val block: TodayBlockKind?,
    val work: Duration,
    val rest: Duration = DEFAULT_REST,
    private val clock: Clock = Clock.System,
) {
    val startedAt: Instant = clock.now()

    val elapsed: Duration get() = clock.now() - startedAt
    val remaining: Duration get() = (work - elapsed).coerceAtLeast(Duration.ZERO)
    val onBreak: Boolean get() = remaining == Duration.ZERO && !finished
    val breakRemaining: Duration get() = (work + rest - elapsed).coerceAtLeast(Duration.ZERO)
    val finished: Boolean get() = elapsed >= work + rest

    /** 0..1 through the work period. */
    val fraction: Double get() = (elapsed / work).coerceIn(0.0, 1.0)

    companion object {
        val OPTIONS_MINUTES = listOf(5, 10, 15, 20)
        val DEFAULT_REST: Duration = 5.minutes

        /** A timer of [minutes] (one of [OPTIONS_MINUTES]) for [block]; rest is a fifth of the work, at least a minute. */
        fun forBlock(block: TodayBlockKind?, minutes: Int, clock: Clock = Clock.System): FocusTimer {
            val work = minutes.coerceIn(1, 120).minutes
            return FocusTimer(block, work, (work / 5).coerceAtLeast(1.minutes), clock)
        }
    }
}
