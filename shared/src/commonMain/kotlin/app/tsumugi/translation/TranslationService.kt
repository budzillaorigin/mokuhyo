package app.tsumugi.translation

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.AiResult
import app.tsumugi.ai.prompts.GradeTranslation
import app.tsumugi.db.Translation_attempt
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.linguist.db.LinguistDatabase
import app.tsumugi.linguist.db.Translation_passage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Passages of the linguist pack (read-only). Every call is empty while the pack isn't installed. */
class TranslationRepository(private val db: LinguistDatabase) {
    @Throws(Exception::class)
    suspend fun passages(genre: String? = null, direction: TranslationDirection? = null): List<TranslationPassage> = withContext(Dispatchers.IO) {
        runCatching { db.linguistQueries.passages().executeAsList() }.getOrDefault(emptyList())
            .map { it.toPassage() }
            .filter { (genre == null || it.genre == genre) && (direction == null || it.direction == direction) }
    }

    @Throws(Exception::class)
    suspend fun passage(id: String): TranslationPassage? = withContext(Dispatchers.IO) {
        runCatching { db.linguistQueries.passageById(id).executeAsOneOrNull() }.getOrNull()?.toPassage()
    }

    private fun Translation_passage.toPassage() = TranslationPassage(
        id = id, direction = TranslationDirection.of(direction), genre = genre, level = level, ilr = ilr, title = title,
        text = text, reference = reference, register = register, keyPoints = stringList(key_points), notes = notes,
        origin = PassageOrigin.of(origin_kind), originRef = origin_ref, sightSeconds = sight_seconds.toInt(),
        source = if (verified != 0L) "verified" else source,
    )

    private fun stringList(raw: String): List<String> =
        runCatching { (Json.parseToJsonElement(raw) as JsonArray).map { (it as JsonPrimitive).content } }.getOrDefault(emptyList())
}

/** A graded or self-assessed attempt, as stored (synced) and shown in history. */
data class TranslationAttempt(
    val id: String,
    val passageId: String,
    val direction: TranslationDirection,
    val genre: String,
    val level: String,
    val mode: TranslationMode,
    val sourceText: String,
    val attemptText: String,
    val durationMs: Long,
    val timeLimitMs: Long?,
    /** "LLM" (the grade_translation prompt, always shown with the AI badge) or "SELF". */
    val grader: String,
    val accuracy: Int,
    val completeness: Int,
    val register: Int,
    val naturalness: Int,
    val score: Int,
    val grade: GradeTranslation.Output?,
    val engine: String?,
    val createdAt: Long,
) {
    val isAiGraded: Boolean get() = grader == GRADER_LLM
    val overTime: Boolean get() = timeLimitMs != null && durationMs > timeLimitMs

    companion object {
        const val GRADER_LLM = "LLM"
        const val GRADER_SELF = "SELF"
    }
}

/** What grading produced. The diff is always there (offline); the grade only with a model. */
sealed interface TranslationGradeResult {
    val diff: TranslationDiff?

    /** An AI grade (rule 10: labeled, practice feedback only), already saved as [attempt]. */
    data class Graded(val attempt: TranslationAttempt, val grade: GradeTranslation.Output, val engine: String, override val diff: TranslationDiff?) : TranslationGradeResult

    /**
     * No model, or it failed: the UI shows the reference, the diff and the self-assessment rubric
     * ([TranslationRubric]); nothing is saved until the learner scores themselves with [TranslationService.selfAssess].
     */
    data class Unavailable(val reason: String, override val diff: TranslationDiff?) : TranslationGradeResult
}

/** One point on the skill line. */
data class SkillPoint(val day: String, val score: Int, val genre: String, val direction: TranslationDirection, val aiGraded: Boolean)

/**
 * The translation skill line on Me (D-274): every live attempt in time order, the day averages for the chart, the
 * average of the last [RECENT] attempts per direction and per genre, and the trend (last five against the five before).
 */
data class TranslationSkillLine(
    val points: List<SkillPoint>,
    val daily: List<Pair<String, Int>>,
    val recentByDirection: Map<TranslationDirection, Int>,
    val recentByGenre: Map<String, Int>,
    val trend: Int?,
    val attempts: Int,
) {
    companion object {
        const val RECENT = 10
    }
}

/**
 * The translation workbench (BRIEF_V2 §6.12; D-270…D-274): passages by genre and direction, timed sight translation
 * (the platform records and transcribes; this takes the transcript) and written mode, grading against the reference with
 * the learner's model, the self-assessment rubric without one, and the skill line.
 */
class TranslationService(
    private val userDb: TsumugiDatabase,
    private val repository: suspend () -> TranslationRepository?,
    private val gateway: suspend () -> AiGateway,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val q get() = userDb.workbenchQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** True when the linguist pack is installed (otherwise the workbench shows an honest empty state plus import). */
    @Throws(Exception::class)
    suspend fun available(): Boolean = repository()?.passages()?.isNotEmpty() == true

    @Throws(Exception::class)
    suspend fun passages(genre: String? = null, direction: TranslationDirection? = null): List<TranslationPassage> =
        repository()?.passages(genre, direction).orEmpty()

    @Throws(Exception::class)
    suspend fun passage(id: String): TranslationPassage? = repository()?.passage(id)

    /** The learner's own text to translate: no reference; the model grades against the source. */
    fun importPassage(text: String, title: String = "", genre: String = "news", direction: TranslationDirection? = null): TranslationPassage =
        ImportedPassage.of(text, title, genre, direction)

    /** Sight-translation limit in milliseconds. */
    fun timeLimitMs(passage: TranslationPassage): Long = passage.sightSeconds * 1000L

    /**
     * Grades [attempt] (typed, or the STT transcript in [TranslationMode.SIGHT]) with the model and saves the attempt.
     * Without a model returns [TranslationGradeResult.Unavailable] with the diff; call [selfAssess] after the rubric.
     */
    @Throws(Exception::class)
    suspend fun grade(passage: TranslationPassage, attempt: String, mode: TranslationMode, durationMs: Long): TranslationGradeResult {
        val text = attempt.trim()
        val diff = if (passage.hasReference && text.isNotEmpty()) TranslationDiff.of(text, passage.reference) else null
        if (text.isEmpty()) return TranslationGradeResult.Unavailable("Write or speak a translation first", diff)
        val input = GradeTranslation.Input(
            source = passage.text, attempt = text, reference = passage.reference,
            japaneseToEnglish = passage.direction == TranslationDirection.JE, genre = passage.genre,
            level = passage.level.ifBlank { "N2" }, spoken = mode == TranslationMode.SIGHT, keyPoints = passage.keyPoints,
            register = passage.register,
        )
        return when (val r = gateway().run(GradeTranslation(), input)) {
            is AiResult.Ok -> {
                val g = r.value
                val saved = save(passage, text, mode, durationMs, TranslationAttempt.GRADER_LLM, g.accuracy, g.completeness, g.register, g.naturalness, g, r.engine)
                TranslationGradeResult.Graded(saved, g, r.engine, diff)
            }
            is AiResult.Fallback -> TranslationGradeResult.Unavailable(r.reason, diff)
            is AiResult.Unavailable -> TranslationGradeResult.Unavailable(r.reason, diff)
        }
    }

    /** Saves a self-assessed attempt (scores 0–4 on [TranslationRubric.criteria]). */
    @Throws(Exception::class)
    suspend fun selfAssess(
        passage: TranslationPassage,
        attempt: String,
        mode: TranslationMode,
        durationMs: Long,
        accuracy: Int,
        completeness: Int,
        register: Int,
        naturalness: Int,
    ): TranslationAttempt = save(passage, attempt.trim(), mode, durationMs, TranslationAttempt.GRADER_SELF, accuracy, completeness, register, naturalness, null, null)

    @Throws(Exception::class)
    suspend fun history(passageId: String? = null): List<TranslationAttempt> = withContext(Dispatchers.IO) {
        (if (passageId == null) q.liveAttempts().executeAsList() else q.attemptsForPassage(passageId).executeAsList()).map { it.toAttempt() }
    }

    /** Deletes an attempt everywhere (a one-way tombstone that syncs, like reviews). */
    @Throws(Exception::class)
    suspend fun delete(id: String) = withContext(Dispatchers.IO) { q.tombstoneAttempt(clock.now().toEpochMilliseconds(), id) }

    @Throws(Exception::class)
    suspend fun skillLine(): TranslationSkillLine = withContext(Dispatchers.IO) { skillLineOf(q.liveAttempts().executeAsList().map { it.toAttempt() }) }

    internal fun skillLineOf(attempts: List<TranslationAttempt>): TranslationSkillLine {
        val tz = timeZone()
        val ordered = attempts.sortedBy { it.createdAt }
        val points = ordered.map {
            SkillPoint(Instant.fromEpochMilliseconds(it.createdAt).toLocalDateTime(tz).date.toString(), it.score, it.genre, it.direction, it.isAiGraded)
        }
        val daily = points.groupBy { it.day }.map { (day, ps) -> day to avg(ps.map { it.score }) }
        val recentByDirection = points.groupBy { it.direction }.mapValues { (_, ps) -> avg(ps.takeLast(TranslationSkillLine.RECENT).map { it.score }) }
        val recentByGenre = points.groupBy { it.genre }.mapValues { (_, ps) -> avg(ps.takeLast(TranslationSkillLine.RECENT).map { it.score }) }
        val trend = if (points.size >= 10) avg(points.takeLast(5).map { it.score }) - avg(points.dropLast(5).takeLast(5).map { it.score }) else null
        return TranslationSkillLine(points, daily, recentByDirection, recentByGenre, trend, points.size)
    }

    private fun avg(xs: List<Int>): Int = if (xs.isEmpty()) 0 else (xs.sum() * 2 + xs.size) / (2 * xs.size)

    private suspend fun save(
        passage: TranslationPassage,
        attempt: String,
        mode: TranslationMode,
        durationMs: Long,
        grader: String,
        accuracy: Int,
        completeness: Int,
        register: Int,
        naturalness: Int,
        grade: GradeTranslation.Output?,
        engine: String?,
    ): TranslationAttempt = withContext(Dispatchers.IO) {
        val scores = listOf(accuracy, completeness, register, naturalness).map { it.coerceIn(0, 4) }
        val id = Uuid.random().toString()
        val now = clock.now().toEpochMilliseconds()
        val limit = if (mode == TranslationMode.SIGHT) timeLimitMs(passage) else null
        q.insertAttempt(
            id, passage.id, passage.direction.code, passage.genre, passage.level, mode.name, passage.text, attempt, durationMs.coerceAtLeast(0),
            limit, grader, scores[0].toLong(), scores[1].toLong(), scores[2].toLong(), scores[3].toLong(),
            TranslationRubric.percent(scores[0], scores[1], scores[2], scores[3]).toLong(),
            grade?.let { json.encodeToString(GradeTranslation.Output.serializer(), it) }, engine, deviceId, now,
        )
        q.attemptById(id).executeAsOne().toAttempt()
    }

    private fun Translation_attempt.toAttempt() = TranslationAttempt(
        id = id, passageId = passage_id, direction = TranslationDirection.of(direction), genre = genre, level = level,
        mode = if (mode == TranslationMode.SIGHT.name) TranslationMode.SIGHT else TranslationMode.WRITTEN,
        sourceText = source_text, attemptText = attempt_text, durationMs = duration_ms, timeLimitMs = time_limit_ms, grader = grader,
        accuracy = accuracy.toInt(), completeness = completeness.toInt(), register = register.toInt(), naturalness = naturalness.toInt(),
        score = score.toInt(),
        grade = grade?.let { runCatching { json.decodeFromString(GradeTranslation.Output.serializer(), it) }.getOrNull() },
        engine = engine, createdAt = created_at,
    )
}
