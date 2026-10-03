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
                endedAt = clock.now().toEpochMilliseconds(),
                turnsJson = json.encodeToString(StoredConversation.serializer(), stored),
                ratingJson = rating?.let { json.encodeToString(OpiRating.serializer(), it) },
                rollingLevelJson = json.encodeToString(ListSerializer(String.serializer()), rollingLevels),
                audioDir = audioDir, deleted = null, correctionsMode = mode?.id,
                aabJson = aab?.let { json.encodeToString(app.mokuhyo.opi.AfterActionBrief.serializer(), it) },
            ),
        )
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

    fun list(learnerId: String, lang: String): List<Conversation> = db.historyQueries.conversations(learnerId, lang).executeAsList()

    fun get(id: String): Conversation? = db.historyQueries.conversation(id).executeAsOneOrNull()

    fun stored(c: Conversation): StoredConversation = json.decodeFromString(StoredConversation.serializer(), c.turnsJson)

    fun rating(c: Conversation): OpiRating? = c.ratingJson?.let { json.decodeFromString(OpiRating.serializer(), it) }

    fun rollingLevels(c: Conversation): List<String> = c.rollingLevelJson?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }.orEmpty()

    fun recordings(conversationId: String): List<Recording> = db.historyQueries.recordingsFor(conversationId).executeAsList()

    fun tombstone(id: String) = db.historyQueries.tombstoneConversation(clock.now().toEpochMilliseconds(), id)
}
