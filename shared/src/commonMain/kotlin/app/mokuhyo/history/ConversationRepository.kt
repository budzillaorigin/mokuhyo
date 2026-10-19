package app.mokuhyo.history

import app.mokuhyo.db.Conversation
import app.mokuhyo.db.MokuhyoDatabase
import app.mokuhyo.db.Recording
import app.mokuhyo.opi.OpiRating
import app.mokuhyo.opi.OpiTurnRecord
import app.mokuhyo.opi.TopicExchange
import app.mokuhyo.opi.Turn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What a conversation row's turnsJson holds for each kind. */
@Serializable
data class StoredConversation(
    val transcript: List<Turn>,
    /** OPI: the interviewer log with phases, target levels and outcomes. */
    val opiTurns: List<OpiTurnRecord> = emptyList(),
    /** Topic: exchanges with corrections, rewrites and vocabulary. */
    val exchanges: List<TopicExchange> = emptyList(),
    /** English glosses of the interviewer's lines (shown after the interview). */
    val english: List<String> = emptyList(),
    val engine: String? = null,
    /** OPI: the cultural-appropriateness review (not part of the ILR scale; BRIEF_PHASE8 C-06). */
    val cultural: app.mokuhyo.opi.CulturalReview? = null,
    /** Interpretation drill (BRIEF_PHASE8 N-01): every chunk with the rendering and its grade. */
    val interpret: List<app.mokuhyo.opi.InterpretResult> = emptyList(),
    // Interviews (QC follow-up F4): how it was answered and where it stands in the speaking trend, so every view can
    // label it the same way. Null on records saved before 2026-10-19 (and on other kinds): unknown, never guessed.
    val spokenAnswers: Int? = null,
    val typedAnswers: Int? = null,
    val textFallback: Boolean? = null,
    val calibrated: Boolean? = null,
    val trend: app.mokuhyo.opi.SpeakingTrend.Status? = null,
    val trendReason: String? = null,
    /** Immutable recovery snapshots use ordinary conversation rows, so old bundle readers still keep them. */
    val draft: ConversationDraft? = null,
)

@Serializable
data class ConversationDraft(
    val sessionId: String, val savedAt: Long,
    val opi: app.mokuhyo.opi.OpiCheckpoint? = null,
    val topic: app.mokuhyo.opi.TopicCheckpoint? = null,
)

/**
 * Interviews and topic conversations with their transcripts, ratings, rolling levels and recordings (BRIEF §8.1).
 * Append-only: a conversation row is written when it ends; recordings are added as turns are recorded; deletes are
 * tombstones.
 */
class ConversationRepository(private val db: MokuhyoDatabase, private val clock: Clock = Clock.System) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun newId(): String = Uuid.random().toString()

    fun save(
        id: String, learnerId: String, lang: String, kind: String, topic: String?, startedAt: Long, stored: StoredConversation,
        rating: OpiRating?, rollingLevels: List<String>, audioDir: String?,
        mode: app.mokuhyo.opi.CorrectionsMode? = null, aab: app.mokuhyo.opi.AfterActionBrief? = null,
    ) {
        db.historyQueries.upsertConversation(
            Conversation(
                id = id, learnerId = learnerId, lang = lang, kind = kind, topic = topic, startedAt = startedAt,
                endedAt = if (stored.draft != null) null else clock.now().toEpochMilliseconds(),
                turnsJson = json.encodeToString(StoredConversation.serializer(), stored),
                ratingJson = rating?.let { json.encodeToString(OpiRating.serializer(), it) },
                rollingLevelJson = json.encodeToString(ListSerializer(String.serializer()), rollingLevels),
                audioDir = audioDir, deleted = null, correctionsMode = mode?.id,
                aabJson = aab?.let { json.encodeToString(app.mokuhyo.opi.AfterActionBrief.serializer(), it) },
            ),
        )
    }

    /** Append a snapshot before calling slow/external work. Never rewrite a prior snapshot or completed result. */
    fun checkpoint(id: String, learnerId: String, lang: String, kind: String, topic: String?, startedAt: Long,
        stored: StoredConversation, mode: app.mokuhyo.opi.CorrectionsMode) {
        require(stored.draft?.sessionId == id)
        save("$id:checkpoint:${newId()}", learnerId, lang, kind, topic, startedAt, stored, null, emptyList(), "recordings/$id", mode)
    }

    /** Records one learner turn's feedback (BRIEF_PHASE8 C-11); the same record in Live and After-action mode. */
    fun addFeedback(conversationId: String, record: app.mokuhyo.opi.TurnFeedbackRecord) {
        db.historyQueries.insertTurnFeedback(
            app.mokuhyo.db.Conversation_turn_feedback("$conversationId:${record.turnIndex}:${clock.now().toEpochMilliseconds()}:${record.learner.hashCode()}", conversationId, record.turnIndex.toLong(),
                json.encodeToString(app.mokuhyo.opi.TurnFeedbackRecord.serializer(), record), clock.now().toEpochMilliseconds(), null),
        )
    }

    fun feedback(conversationId: String): List<app.mokuhyo.opi.TurnFeedbackRecord> = db.historyQueries.turnFeedback(conversationId).executeAsList()
        .map { json.decodeFromString(app.mokuhyo.opi.TurnFeedbackRecord.serializer(), it.json) }

    fun mode(c: Conversation): app.mokuhyo.opi.CorrectionsMode = app.mokuhyo.opi.CorrectionsMode.of(c.correctionsMode) ?: app.mokuhyo.opi.CorrectionsMode.LIVE

    fun aab(c: Conversation): app.mokuhyo.opi.AfterActionBrief? =
        c.aabJson?.let { runCatching { json.decodeFromString(app.mokuhyo.opi.AfterActionBrief.serializer(), it) }.getOrNull() }

    fun addRecording(conversationId: String, turnIndex: Int, path: String, durationMs: Long, sha256: String) {
        db.historyQueries.insertRecording(Recording(Uuid.random().toString(), conversationId, null, turnIndex.toLong(), path, durationMs, sha256, clock.now().toEpochMilliseconds(), null))
    }

    fun list(learnerId: String, lang: String): List<Conversation> {
        val all = db.historyQueries.conversations(learnerId, lang).executeAsList()
        val completed = all.filter { stored(it).draft == null }.associateBy { it.id }
        val drafts = all.filter { stored(it).draft != null }.groupBy { stored(it).draft!!.sessionId }
            .filterKeys { it !in completed && get(it)?.deleted == null }
            .values.map { snapshots -> snapshots.maxWith(compareBy<Conversation> { stored(it).draft!!.savedAt }.thenBy { it.id }) }
        return (completed.values + drafts).sortedByDescending { it.startedAt }
    }

    fun get(id: String): Conversation? = db.historyQueries.conversation(id).executeAsOneOrNull()

    fun stored(c: Conversation): StoredConversation = json.decodeFromString(StoredConversation.serializer(), c.turnsJson)

    fun rating(c: Conversation): OpiRating? = c.ratingJson?.let { json.decodeFromString(OpiRating.serializer(), it) }

    fun rollingLevels(c: Conversation): List<String> = c.rollingLevelJson?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }.orEmpty()

    /**
     * The rating's label wherever it's shown (QC follow-up F4); null without an estimate. [speakingRows] maps a
     * conversation id to the provisional flag of its speaking-estimate row (see [speakingRows]).
     */
    fun speakingLabel(c: Conversation, speakingRows: Map<String, Boolean>): app.mokuhyo.opi.SpeakingTrend.Label? {
        val r = rating(c) ?: return null
        val estimate = r.estimate ?: return null
        val s = stored(c)
        return app.mokuhyo.opi.SpeakingTrend.label(c.kind, estimate, r.engine != null, r.selfRated, s.trend, s.trendReason, speakingRows[c.id])
    }

    fun recordings(conversationId: String): List<Recording> = db.historyQueries.recordingsFor(
        get(conversationId)?.let { stored(it).draft?.sessionId } ?: conversationId).executeAsList()

    fun tombstone(id: String) {
        val root = get(id)?.let { stored(it).draft?.sessionId } ?: id
        val row = get(id) ?: return
        db.transaction {
            db.historyQueries.conversations(row.learnerId, row.lang).executeAsList().filter {
                it.id == root || stored(it).draft?.sessionId == root
            }.forEach { db.historyQueries.tombstoneConversation(clock.now().toEpochMilliseconds(), it.id) }
        }
    }
}
