package app.tsumugi.poetry

import app.tsumugi.db.Circle_session
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.reader.ReaderAnalyzer
import app.tsumugi.reader.ReaderSentence
import app.tsumugi.reader.RubyHint
import app.tsumugi.recordings.PendingRecording
import app.tsumugi.recordings.Recording
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.RecordingStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What the learner did with one sentence: read it aloud, then explained it in English (typed and/or recorded). */
@Serializable
data class CircleEntry(
    val idx: Int,
    /** Recording id of the read-aloud (RecordingStore, device-local unless recordings sync is on). */
    val read: String? = null,
    /** The typed English explanation. */
    val explain: String = "",
    /** Recording id of a spoken explanation. */
    val explainRec: String? = null,
    val doneAt: Long? = null,
) {
    val done: Boolean get() = doneAt != null
    val explained: Boolean get() = explain.isNotBlank() || explainRec != null
}

/**
 * A solo reading-circle session (USJETAA's format, alone; BRIEF_V2 §6.14, D-278): one text, sentence by sentence,
 * read aloud then explained. Pure state: every change returns a new session; [ReadingCircle] persists it (synced).
 */
data class CircleSession(
    val id: String,
    /** A linguist-pack circle text id, or "doc:<reader document id>" for a text from the learner's library. */
    val textId: String,
    val title: String,
    val sentenceCount: Int,
    val position: Int,
    val entries: List<CircleEntry>,
    val startedAt: Long,
    val finishedAt: Long?,
    val updatedAt: Long,
) {
    val finished: Boolean get() = finishedAt != null
    val doneCount: Int get() = entries.count { it.done }

    fun entry(idx: Int): CircleEntry = entries.firstOrNull { it.idx == idx } ?: CircleEntry(idx)

    fun withReading(idx: Int, recordingId: String, now: Long): CircleSession = edit(idx, now) { it.copy(read = recordingId) }

    fun withExplanation(idx: Int, text: String, now: Long): CircleSession = edit(idx, now) { it.copy(explain = text.trim()) }

    fun withExplanationRecording(idx: Int, recordingId: String, now: Long): CircleSession = edit(idx, now) { it.copy(explainRec = recordingId) }

    /**
     * Marks [idx] done (it needs a reading and an explanation, else unchanged) and moves to the next sentence not done.
     * The session finishes when every sentence is done.
     */
    fun completeSentence(idx: Int, now: Long): CircleSession {
        val e = entry(idx)
        if (e.read == null || !e.explained) return this
        val next = edit(idx, now) { it.copy(doneAt = it.doneAt ?: now) }
        val remaining = (0 until sentenceCount).filter { !next.entry(it).done }
        val following = remaining.firstOrNull { it > idx } ?: remaining.firstOrNull()
        return next.copy(position = following ?: idx, finishedAt = if (remaining.isEmpty()) (finishedAt ?: now) else null)
    }

    fun moveTo(idx: Int, now: Long): CircleSession = copy(position = idx.coerceIn(0, (sentenceCount - 1).coerceAtLeast(0)), updatedAt = now)

    private fun edit(idx: Int, now: Long, change: (CircleEntry) -> CircleEntry): CircleSession {
        require(idx in 0 until sentenceCount) { "sentence $idx is outside 0..${sentenceCount - 1}" }
        val updated = change(entry(idx))
        return copy(entries = (entries.filter { it.idx != idx } + updated).sortedBy { it.idx }, updatedAt = now)
    }
}

/** Dictionary and grammar help for one sentence: the reader's tokens (tap a word) and the grammar points it contains. */
data class CircleHelp(val sentence: ReaderSentence, val grammar: List<Pair<String, String>>)

/** A text the circle can read: its sentences are the reader's own split, so help and recordings line up. */
data class CircleReading(val textId: String, val title: String, val body: String, val ruby: List<RubyHint>, val sentences: List<CircleSentence>)

/**
 * The solo reading circle: pick a short Aozora text (the pack's circle texts or any document in the learner's
 * library), read each sentence aloud (recorded with [RecordingStore], kind FREE, ref "circle:<session>/<idx>/read"),
 * then explain it in English (typed or recorded, ref ".../explain"). Recordings are kept; each sentence shows its
 * dictionary and grammar help. Sessions sync (LWW); their recordings follow the device's recordings-sync setting.
 */
class ReadingCircle(
    private val userDb: TsumugiDatabase,
    private val poetry: suspend () -> PoetryRepository?,
    private val recordings: RecordingStore,
    private val analyzer: suspend () -> ReaderAnalyzer?,
    private val libraryDocument: suspend (String) -> Pair<String, Pair<String, List<RubyHint>>>? = { null },
    private val grammarTitle: suspend (String) -> String? = { null },
    private val clock: Clock = Clock.System,
) {
    private val q get() = userDb.workbenchQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val entriesSerializer = ListSerializer(CircleEntry.serializer())

    /** The texts the circle offers from the pack (empty without it). */
    @Throws(Exception::class)
    suspend fun texts(): List<CircleTextSummary> = poetry()?.circleTexts().orEmpty()

    /** The text of [textId] split into sentences: a pack text, or "doc:<id>" from the reader library. */
    @Throws(Exception::class)
    suspend fun reading(textId: String): CircleReading? {
        if (textId.startsWith(DOC)) {
            val (title, content) = libraryDocument(textId.removePrefix(DOC)) ?: return null
            val (body, ruby) = content
            return CircleReading(textId, title, body, ruby, splitSentences(body))
        }
        val t = poetry()?.circleText(textId) ?: return null
        return CircleReading(t.id, t.title, t.body, t.ruby, t.sentences)
    }

    /** Resumes the unfinished session on [textId], or starts one. */
    @Throws(Exception::class)
    suspend fun start(textId: String): CircleSession? {
        sessions().firstOrNull { it.textId == textId && !it.finished }?.let { return it }
        val reading = reading(textId) ?: return null
        if (reading.sentences.isEmpty()) return null
        val now = clock.now().toEpochMilliseconds()
        return save(CircleSession(Uuid.random().toString(), textId, reading.title, reading.sentences.size, 0, emptyList(), now, null, now))
    }

    @Throws(Exception::class)
    suspend fun sessions(): List<CircleSession> = io { q.liveCircleSessions().executeAsList().map { it.toSession() } }

    @Throws(Exception::class)
    suspend fun session(id: String): CircleSession? = io { q.circleSessionById(id).executeAsOneOrNull()?.takeIf { it.deleted == 0L }?.toSession() }

    /** Where the platform records the read-aloud of sentence [idx]. */
    @Throws(Exception::class)
    suspend fun startRecording(): PendingRecording = recordings.startRecording()

    /** Registers a finished read-aloud recording and links it to the sentence. */
    @Throws(Exception::class)
    suspend fun attachReading(session: CircleSession, idx: Int, pending: PendingRecording, durationMs: Long): CircleSession {
        val rec = recordings.register(pending, RecordingKind.FREE, ref(session, idx, "read"), durationMs)
        return save(session.withReading(idx, rec.id, now()))
    }

    /** Registers a spoken explanation (English) and links it to the sentence. */
    @Throws(Exception::class)
    suspend fun attachExplanationRecording(session: CircleSession, idx: Int, pending: PendingRecording, durationMs: Long): CircleSession {
        val rec = recordings.register(pending, RecordingKind.FREE, ref(session, idx, "explain"), durationMs)
        return save(session.withExplanationRecording(idx, rec.id, now()))
    }

    @Throws(Exception::class)
    suspend fun explain(session: CircleSession, idx: Int, text: String): CircleSession = save(session.withExplanation(idx, text, now()))

    @Throws(Exception::class)
    suspend fun complete(session: CircleSession, idx: Int): CircleSession = save(session.completeSentence(idx, now()))

    @Throws(Exception::class)
    suspend fun moveTo(session: CircleSession, idx: Int): CircleSession = save(session.moveTo(idx, now()))

    /** The recordings kept for sentence [idx] (read-aloud first), newest first within each. */
    @Throws(Exception::class)
    suspend fun recordingsOf(session: CircleSession, idx: Int): List<Recording> =
        recordings.recordingsFor(RecordingKind.FREE, ref(session, idx, "read")) + recordings.recordingsFor(RecordingKind.FREE, ref(session, idx, "explain"))

    /** Dictionary tokens and grammar points of sentence [idx], or null without the dictionary pack. */
    @Throws(Exception::class)
    suspend fun help(reading: CircleReading, idx: Int): CircleHelp? {
        val s = reading.sentences.getOrNull(idx) ?: return null
        val reader = analyzer() ?: return null
        val paragraph = reader.paragraph(reading.body, s.start until s.end, reading.ruby)
        val sentence = paragraph.sentences.firstOrNull() ?: return null
        val grammar = paragraph.sentences.flatMap { it.grammarPointIds }.distinct().map { it to (grammarTitle(it) ?: it) }
        return CircleHelp(if (paragraph.sentences.size == 1) sentence else sentence.copy(text = s.text, start = s.start, end = s.end, tokens = paragraph.sentences.flatMap { it.tokens }), grammar)
    }

    @Throws(Exception::class)
    suspend fun delete(session: CircleSession) = io { q.deleteCircleSession(now(), session.id) }

    private suspend fun save(s: CircleSession): CircleSession = io {
        q.putCircleSession(s.id, s.textId, s.title, s.sentenceCount.toLong(), s.position.toLong(), json.encodeToString(entriesSerializer, s.entries), s.startedAt, s.finishedAt, s.updatedAt)
        s
    }

    private fun Circle_session.toSession() = CircleSession(
        id, text_id, title, sentence_count.toInt(), position.toInt(),
        runCatching { json.decodeFromString(entriesSerializer, entries) }.getOrDefault(emptyList()), started_at, finished_at, updated_at,
    )

    private fun ref(session: CircleSession, idx: Int, part: String) = "circle:${session.id}/$idx/$part"

    private fun now() = clock.now().toEpochMilliseconds()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DOC = "doc:"

        /** ReaderAnalyzer.sentences over every paragraph (the same split the pack builder uses). */
        fun splitSentences(body: String): List<CircleSentence> {
            val analyzer = SENTENCE_SPLITTER
            var n = 0
            return analyzer.paragraphs(body).flatMap { analyzer.sentences(body, it) }.map { r ->
                CircleSentence(n++, r.first, r.last + 1, body.substring(r.first, r.last + 1))
            }
        }

        private val SENTENCE_SPLITTER = ReaderAnalyzer({ emptyList() }, { emptyList() }, { emptyMap() })
    }
}
