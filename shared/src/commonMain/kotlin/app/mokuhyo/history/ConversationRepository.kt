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
    /** Set on a revision row: a later version of a completed conversation (e.g. a self-rating), appended, never replacing it. */
    val revision: ConversationRevision? = null,
)

/** A revision row's link to the completed conversation it revises; the latest live revision (by [savedAt], then row id) is shown. */
@Serializable
data class ConversationRevision(val of: String, val savedAt: Long)

@Serializable
data class ConversationDraft(
    val sessionId: String, val savedAt: Long,
    val opi: app.mokuhyo.opi.OpiCheckpoint? = null,
    val topic: app.mokuhyo.opi.TopicCheckpoint? = null,
)

/**
 * Interviews and topic conversations with their transcripts, ratings, rolling levels and recordings (BRIEF §8.1).
 * Append-only: a conversation row is written when it ends; recordings are added as turns are recorded; deletes are
 * tombstones. Saving an id that is already complete (a self-rating after the interview) appends a revision row instead
 * of replacing it, so the original fact survives and bundles merge the change like any other new row (QC Q2).
 */
class ConversationRepository(private val db: MokuhyoDatabase, private val clock: Clock = Clock.System) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun newId(): String = Uuid.random().toString()

    fun save(
        id: String, learnerId: String, lang: String, kind: String, topic: String?, startedAt: Long, stored: StoredConversation,
        rating: OpiRating?, rollingLevels: List<String>, audioDir: String?,
        mode: app.mokuhyo.opi.CorrectionsMode? = null, aab: app.mokuhyo.opi.AfterActionBrief? = null,
    ) {
        val now = clock.now().toEpochMilliseconds()
        fun row(rowId: String, s: StoredConversation) = Conversation(
            id = rowId, learnerId = learnerId, lang = lang, kind = kind, topic = topic, startedAt = startedAt,
            endedAt = if (s.draft != null) null else now,
            turnsJson = json.encodeToString(StoredConversation.serializer(), s),
            ratingJson = rating?.let { json.encodeToString(OpiRating.serializer(), it) },
            rollingLevelJson = json.encodeToString(ListSerializer(String.serializer()), rollingLevels),
            audioDir = audioDir, deleted = null, correctionsMode = mode?.id,
            aabJson = aab?.let { json.encodeToString(app.mokuhyo.opi.AfterActionBrief.serializer(), it) },
        )
        db.transaction {
            val existing = get(id)
            if (existing == null) {
                db.historyQueries.insertConversationIfAbsent(row(id, stored))
                return@transaction
            }
            val candidate = row(id, stored.copy(revision = null))
            if (candidate.copy(endedAt = existing.endedAt) == existing) return@transaction
            val revision = stored.copy(draft = null, revision = ConversationRevision(id, now))
            db.historyQueries.insertConversationIfAbsent(row("$id:revision:${newId()}", revision))
        }
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
        val revisions = all.filter { stored(it).revision != null }.groupBy { stored(it).revision!!.of }
        val completed = all.filter { stored(it).draft == null && stored(it).revision == null }.associateBy { it.id }
        val drafts = all.filter { stored(it).draft != null }.groupBy { stored(it).draft!!.sessionId }
            .filterKeys { it !in completed && get(it)?.deleted == null }
            .values.map { snapshots -> snapshots.maxWith(compareBy<Conversation> { stored(it).draft!!.savedAt }.thenBy { it.id }) }
        return (completed.values.map { latest(it, revisions[it.id].orEmpty()) } + drafts).sortedByDescending { it.startedAt }
    }

    /** The conversation as shown: [id]'s row with the content of its latest live revision, if any. */
    fun get(id: String): Conversation? {
        val base = db.historyQueries.conversation(id).executeAsOneOrNull() ?: return null
        if (stored(base).draft != null || stored(base).revision != null) return base
        return latest(base, db.historyQueries.conversationRevisions("$id:revision:%").executeAsList())
    }

    /** Deterministic on every computer: the newest live revision by savedAt, then row id; its content under [base]'s id. */
    private fun latest(base: Conversation, revisions: List<Conversation>): Conversation {
        val r = revisions.filter { it.deleted == null && stored(it).revision?.of == base.id }
            .maxWithOrNull(compareBy<Conversation> { stored(it).revision!!.savedAt }.thenBy { it.id }) ?: return base
        return r.copy(id = base.id, startedAt = base.startedAt, endedAt = base.endedAt, deleted = base.deleted,
            turnsJson = json.encodeToString(StoredConversation.serializer(), stored(r).copy(revision = null)))
    }

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
                it.id == root || stored(it).draft?.sessionId == root || stored(it).revision?.of == root
            }.forEach { db.historyQueries.tombstoneConversation(clock.now().toEpochMilliseconds(), it.id) }
        }
    }
}
