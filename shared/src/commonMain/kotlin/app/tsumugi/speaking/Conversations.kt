package app.tsumugi.speaking

import app.tsumugi.ai.prompts.CorrectSentence
import app.tsumugi.db.Conversation
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.jp.Deinflector
import app.tsumugi.jp.Kana
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

enum class ConversationMode { FREE_TALK, SCENARIO }

/** One stored line. [corrections] is the `correct_sentence` result for a learner line the learner asked to check. */
@Serializable
data class ConversationTurn(
    val role: String,
    val ja: String,
    val en: String = "",
    val corrections: CorrectSentence.Output? = null,
) {
    val isLearner: Boolean get() = role == LEARNER

    companion object {
        const val LEARNER = "learner"
        const val PARTNER = "partner"
    }
}

/** What kind of mistake a `correct_sentence` edit fixed (heuristic, see [ErrorClassifier]). */
enum class ErrorType { PARTICLE, CONJUGATION, TENSE, POLITENESS, SPELLING, WORD_CHOICE, OTHER }

@Serializable
data class ConversationError(val type: ErrorType, val original: String, val replacement: String, val reason: String = "")

/**
 * A heuristic level for one conversation or a rolling window of them (DECISIONS D-102). [score] 0–100; [jlpt] 5..1;
 * [ilr] "0+".."3". Practice feedback, never a rating.
 */
@Serializable
data class ConversationLevel(val score: Int, val jlpt: Int, val ilr: String, val learnerTurns: Int) {
    val jlptLabel: String get() = "N$jlpt"
}

/** The rolling estimate over recent conversations: what free talk speaks at, and the Me screen shows. */
data class RollingLevel(val level: ConversationLevel, val conversations: Int)

data class ConversationRecord(
    val id: String,
    val mode: ConversationMode,
    val scenarioId: String?,
    val startedAt: Long,
    val endedAt: Long,
    /** JLPT label the partner spoke at. */
    val level: String,
    val turns: List<ConversationTurn>,
    val levelEstimate: ConversationLevel?,
    val errors: List<ConversationError>,
    /** Model that wrote the partner lines (AI-generated badge), null for scripted. */
    val engine: String?,
) {
    val learnerTurns: Int get() = turns.count { it.isLearner }
}

data class ErrorExample(val original: String, val replacement: String)

/** A recurring error type: how often it came up in the window, with a few examples, and the previous window's count. */
data class ErrorPattern(val type: ErrorType, val count: Int, val examples: List<ErrorExample>, val previousCount: Int = 0) {
    val trend: Int get() = count - previousCount
}

/** The weekly "patterns" card on Me (BRIEF §5.10: surfaced weekly). */
data class WeeklyPatterns(
    val conversations: Int,
    val learnerTurns: Int,
    val minutes: Int,
    /** Error types this week, most frequent first, with last week's counts for comparison. */
    val errors: List<ErrorPattern>,
    val level: RollingLevel?,
    /** Change of the rolling score against the estimate as it was a week ago; null without both. */
    val levelChange: Int?,
) {
    val isEmpty: Boolean get() = conversations == 0
}

/**
 * Classifies a `correct_sentence` edit into an [ErrorType] with plain rules (no model): particles by a closed list,
 * tense and politeness by endings, conjugation by a shared dictionary form (deinflector), spelling by the same
 * reading in kana. Everything else is a word choice (content words changed) or OTHER.
 */
object ErrorClassifier {
    private val particles = setOf(
        "は", "が", "を", "に", "で", "へ", "と", "も", "の", "や", "から", "まで", "より", "か", "ね", "よ", "には", "では", "とは",
        "にも", "でも", "へは", "からは", "までに",
    )
    private val politeEndings = listOf("です", "ます", "ました", "ません", "でした", "ましょう", "ください")
    private val pastEndings = listOf("た", "だ", "ました", "でした", "かった")

    fun classify(original: String, replacement: String): ErrorType {
        val a = original.trim().trimEnd('。', '、', '！', '？')
        val b = replacement.trim().trimEnd('。', '、', '！', '？')
        if (a.isEmpty() && b.isEmpty()) return ErrorType.OTHER
        if ((a.isEmpty() || a in particles) && (b.isEmpty() || b in particles)) return ErrorType.PARTICLE
        if (stripParticle(a) == stripParticle(b) && a != b) return ErrorType.PARTICLE
        if (a.isNotEmpty() && b.isNotEmpty() && Kana.toHiragana(a) == Kana.toHiragana(b)) return ErrorType.SPELLING
        if (a.isNotEmpty() && b.isNotEmpty() && sameBase(a, b)) {
            val aPolite = politeEndings.any { a.endsWith(it) }
            val bPolite = politeEndings.any { b.endsWith(it) }
            if (aPolite != bPolite) return ErrorType.POLITENESS
            val aPast = pastEndings.any { a.endsWith(it) }
            val bPast = pastEndings.any { b.endsWith(it) }
            if (aPast != bPast) return ErrorType.TENSE
            return ErrorType.CONJUGATION
        }
        if (a.isNotEmpty() && b.isNotEmpty() && (Kana.containsKanji(a) || Kana.containsKanji(b) || Kana.isJapanese(a))) return ErrorType.WORD_CHOICE
        return ErrorType.OTHER
    }

    fun classify(edit: CorrectSentence.Edit): ConversationError =
        ConversationError(classify(edit.original, edit.replacement), edit.original, edit.replacement, edit.reason)

    private fun stripParticle(s: String): String {
        val p = particles.filter { s.endsWith(it) }.maxByOrNull { it.length } ?: return s
        return s.removeSuffix(p)
    }

    /** Both strings deinflect to a common dictionary form (食べる ← 食べた / 食べます). */
    private fun sameBase(a: String, b: String): Boolean {
        val ta = Deinflector.deinflect(a).filter { it.reasons.isNotEmpty() || it.term == a }.map { it.term }.toSet()
        val tb = Deinflector.deinflect(b).filter { it.reasons.isNotEmpty() || it.term == b }.map { it.term }.toSet()
        return ta.intersect(tb).any { it.length >= 2 || Kana.containsKanji(it) }
    }
}

/**
 * The heuristic level estimate (DECISIONS D-102). Per conversation (at least [MIN_TURNS] learner lines):
 * length = mean learner line length (characters, punctuation excluded) mapped 4→0 … 40→1; kanji = kanji share of the
 * Japanese characters mapped 0→0 … 0.35→1; accuracy = 1 − (edits per checked line ÷ 2), only when lines were checked.
 * score = 100 × (0.45·length + 0.25·kanji + 0.30·accuracy), or 100 × (0.6·length + 0.4·kanji) with nothing checked.
 * Bands: <20 N5/ILR 0+, <35 N4/1, <50 N3/1+, <65 N2/2, <80 N1/2+, else N1/3. The rolling estimate weights the last
 * [WINDOW] conversations 1, 0.8, 0.64, … newest first.
 */
object LevelEstimator {
    const val MIN_TURNS = 3
    const val WINDOW = 5

    fun estimate(turns: List<ConversationTurn>): ConversationLevel? {
        val learner = turns.filter { it.isLearner && it.ja.isNotBlank() }
        if (learner.size < MIN_TURNS) return null
        val texts = learner.map { t -> t.ja.filterNot { it.isWhitespace() || it in PUNCTUATION } }
        val avgLen = texts.sumOf { it.length }.toDouble() / texts.size
        val japanese = texts.sumOf { s -> s.count { Kana.isKana(it) || Kana.isKanji(it.code) } }
        val kanji = texts.sumOf { s -> s.count { Kana.isKanji(it.code) } }
        val kanjiRatio = if (japanese == 0) 0.0 else kanji.toDouble() / japanese
        val checked = learner.mapNotNull { it.corrections }
        val lengthScore = ((avgLen - 4) / 36).coerceIn(0.0, 1.0)
        val kanjiScore = (kanjiRatio / 0.35).coerceIn(0.0, 1.0)
        val score = if (checked.isEmpty()) {
            100 * (0.6 * lengthScore + 0.4 * kanjiScore)
        } else {
            val editsPerLine = checked.sumOf { if (it.isCorrect) 0 else it.edits.size.coerceAtLeast(1) }.toDouble() / checked.size
            val accuracy = 1 - (editsPerLine / 2).coerceIn(0.0, 1.0)
            100 * (0.45 * lengthScore + 0.25 * kanjiScore + 0.30 * accuracy)
        }
        return level(score.roundToInt(), learner.size)
    }

    /** Weighted mean of [estimates] (newest first). */
    fun rolling(estimates: List<ConversationLevel>): RollingLevel? {
        val window = estimates.take(WINDOW)
        if (window.isEmpty()) return null
        var weight = 1.0
        var sum = 0.0
        var total = 0.0
        for (e in window) {
            sum += e.score * weight
            total += weight
            weight *= 0.8
        }
        return RollingLevel(level((sum / total).roundToInt(), window.sumOf { it.learnerTurns }), window.size)
    }

    fun level(score: Int, turns: Int): ConversationLevel {
        val (jlpt, ilr) = when {
            score < 20 -> 5 to "0+"
            score < 35 -> 4 to "1"
            score < 50 -> 3 to "1+"
            score < 65 -> 2 to "2"
            score < 80 -> 1 to "2+"
            else -> 1 to "3"
        }
        return ConversationLevel(score.coerceIn(0, 100), jlpt, ilr, turns)
    }

    private const val PUNCTUATION = "。、！？!?,.・「」『』（）()…ー〜~"
}

/**
 * Stored conversations (BRIEF §5.1 Conversation, BRIEF_V2 G-02): the rolling level estimate, the recurring-error log
 * and the weekly patterns card. Rows are written once per finished conversation and sync by union (D-101).
 */
class ConversationService(
    private val db: TsumugiDatabase,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.conversationQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val turnsSerializer = ListSerializer(ConversationTurn.serializer())
    private val errorsSerializer = ListSerializer(ConversationError.serializer())

    /**
     * Stores a finished conversation; returns null when there's nothing of the learner's to keep. Errors and the
     * level estimate are computed from the turns here, so every caller stores them the same way.
     */
    @Throws(Exception::class)
    suspend fun save(
        mode: ConversationMode,
        scenarioId: String?,
        level: String,
        turns: List<ConversationTurn>,
        startedAt: Long,
        engine: String?,
    ): ConversationRecord? = withContext(Dispatchers.IO) {
        if (turns.none { it.isLearner }) return@withContext null
        val record = ConversationRecord(
            Uuid.random().toString(), mode, scenarioId, startedAt, clock.now().toEpochMilliseconds(), level, turns,
            LevelEstimator.estimate(turns), errorsOf(turns), engine,
        )
        q.insertConversation(
            record.id, mode.name, scenarioId, record.startedAt, record.endedAt, level,
            json.encodeToString(turnsSerializer, turns),
            record.levelEstimate?.let { json.encodeToString(ConversationLevel.serializer(), it) },
            json.encodeToString(errorsSerializer, record.errors), engine, deviceId,
        )
        record
    }

    @Throws(Exception::class)
    suspend fun recent(limit: Int = 20): List<ConversationRecord> = withContext(Dispatchers.IO) {
        q.recentConversations(limit.toLong()).executeAsList().map { it.toRecord() }
    }

    @Throws(Exception::class)
    suspend fun conversation(id: String): ConversationRecord? = withContext(Dispatchers.IO) {
        q.conversationById(id).executeAsOneOrNull()?.toRecord()
    }

    /** The rolling level over the last conversations that had enough turns to judge; null before any. */
    @Throws(Exception::class)
    suspend fun levelEstimate(): RollingLevel? = withContext(Dispatchers.IO) { rollingAt(Long.MAX_VALUE) }

    /** The level free talk should speak at: the rolling estimate, else [fallbackJlpt]. */
    @Throws(Exception::class)
    suspend fun partnerLevel(fallbackJlpt: Int = 4): String = levelEstimate()?.level?.jlptLabel ?: "N$fallbackJlpt"

    /** Error types from the last [days] days, most frequent first, with up to three recent examples each. */
    @Throws(Exception::class)
    suspend fun recurringErrors(days: Int = 30, minCount: Int = 1): List<ErrorPattern> = withContext(Dispatchers.IO) {
        val now = clock.now()
        val current = since((now - days.days).toEpochMilliseconds())
        val previous = between((now - (2 * days).days).toEpochMilliseconds(), (now - days.days).toEpochMilliseconds())
        patterns(current, previous).filter { it.count >= minCount }
    }

    /** The weekly patterns card: this week's conversations, errors against last week, and the level trend. */
    @Throws(Exception::class)
    suspend fun weeklyPatterns(): WeeklyPatterns = withContext(Dispatchers.IO) {
        val now = clock.now()
        val weekAgo = (now - 7.days).toEpochMilliseconds()
        val week = since(weekAgo)
        val lastWeek = between((now - 14.days).toEpochMilliseconds(), weekAgo)
        val level = rollingAt(Long.MAX_VALUE)
        val before = rollingAt(weekAgo)
        WeeklyPatterns(
            conversations = week.size,
            learnerTurns = week.sumOf { it.learnerTurns },
            minutes = (week.sumOf { (it.endedAt - it.startedAt).coerceAtLeast(0) } / 60_000).toInt(),
            errors = patterns(week, lastWeek),
            level = level,
            levelChange = if (level != null && before != null && week.any { it.levelEstimate != null }) level.level.score - before.level.score else null,
        )
    }

    private fun rollingAt(endedBefore: Long): RollingLevel? {
        val estimates = q.recentConversations(200).executeAsList().asSequence()
            .filter { it.ended_at < endedBefore }
            .mapNotNull { row -> row.level_estimate?.let { runCatching { json.decodeFromString(ConversationLevel.serializer(), it) }.getOrNull() } }
            .take(LevelEstimator.WINDOW).toList()
        return LevelEstimator.rolling(estimates)
    }

    private fun since(ms: Long) = q.conversationsSince(ms).executeAsList().map { it.toRecord() }
    private fun between(from: Long, to: Long) = since(from).filter { it.endedAt < to }

    private fun patterns(current: List<ConversationRecord>, previous: List<ConversationRecord>): List<ErrorPattern> {
        val before = previous.flatMap { it.errors }.groupingBy { it.type }.eachCount()
        return current.flatMap { it.errors }.groupBy { it.type }
            .map { (type, list) -> ErrorPattern(type, list.size, list.take(3).map { ErrorExample(it.original, it.replacement) }, before[type] ?: 0) }
            .sortedWith(compareByDescending<ErrorPattern> { it.count }.thenBy { it.type.ordinal })
    }

    private fun Conversation.toRecord() = ConversationRecord(
        id, runCatching { ConversationMode.valueOf(mode) }.getOrDefault(ConversationMode.FREE_TALK), scenario_id, started_at, ended_at, level,
        runCatching { json.decodeFromString(turnsSerializer, turns) }.getOrDefault(emptyList()),
        level_estimate?.let { runCatching { json.decodeFromString(ConversationLevel.serializer(), it) }.getOrNull() },
        runCatching { json.decodeFromString(errorsSerializer, errors) }.getOrDefault(emptyList()),
        engine,
    )

    companion object {
        /** Every edit of every checked learner line, classified. */
        fun errorsOf(turns: List<ConversationTurn>): List<ConversationError> =
            turns.filter { it.isLearner }.mapNotNull { it.corrections }.filterNot { it.isCorrect }.flatMap { c -> c.edits.map(ErrorClassifier::classify) }
    }
}
