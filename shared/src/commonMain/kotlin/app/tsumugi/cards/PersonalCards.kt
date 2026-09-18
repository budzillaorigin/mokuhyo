package app.tsumugi.cards

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.recordings.ImageStore
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.RecordingStore
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.SrsRepository
import app.tsumugi.srs.StudyItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * What a personal (Fluent Forever-style) card keeps in its item's `context` JSON (DECISIONS D-112). It syncs with
 * the item; the picture and recording it points to are device files that follow only with recordings sync on.
 */
@Serializable
data class PersonalCardContext(
    val type: String = TYPE,
    val word: String,
    val note: String = "",
    /** [app.tsumugi.recordings.UserImage] id. */
    val imageId: String? = null,
    /** [app.tsumugi.recordings.Recording] id of the learner saying the word. */
    val audioRecordingId: String? = null,
) {
    companion object {
        const val TYPE = "personal"
    }
}

/** One side of a card as the review screen draws it. Any field may be null; the UI shows what's there. */
data class CardFace(
    /** Absolute path of a picture on this device; null when there is none or it isn't downloaded. */
    val imagePath: String?,
    /** Absolute path of the learner's recording; null when there is none on this device. */
    val audioPath: String?,
    val text: String?,
    val reading: String?,
    val note: String?,
    /** Speak this with TTS when [audioPath] is missing (audio side of a card whose recording isn't here). */
    val speakFallback: String?,
)

/** Rendering data for a personal card or an item's self-recorded audio side. */
data class PersonalCardRender(
    val itemId: String,
    val direction: CardDirection,
    val front: CardFace,
    val back: CardFace,
    /** Missing files the card refers to ("picture", "recording"): e.g. recordings sync is off on this device. */
    val missing: List<String>,
)

/**
 * Personal cards (BRIEF_V2 G-12, Fluent Forever) and self-recorded audio sides (G-03):
 * - [create] makes a `CUSTOM` item: the learner's picture, their own recording of the word, the word and a note.
 *   With a picture the card is RECALL (picture on the front → say/recall the word); with only a recording it is
 *   LISTENING (hear yourself → recall the meaning); with neither, RECOGNITION.
 * - [addAudioSide] attaches the learner's recording to any item (a vocabulary word, a sentence) and gives it a
 *   LISTENING card whose front plays that recording.
 * - [render] turns a card into [PersonalCardRender] faces.
 */
class PersonalCards(
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val recordings: RecordingStore,
    private val images: ImageStore,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Creates a personal card; returns the item id. [meaning] is optional (Fluent Forever avoids translations). */
    @Throws(Exception::class)
    suspend fun create(
        word: String,
        reading: String? = null,
        meaning: String? = null,
        note: String = "",
        imageId: String? = null,
        audioRecordingId: String? = null,
    ): String {
        val w = normalizeNfc(word.trim())
        require(w.isNotEmpty()) { "a personal card needs a word" }
        val id = "p:" + Uuid.random().toString()
        val ctx = PersonalCardContext(word = w, note = note.trim(), imageId = imageId, audioRecordingId = audioRecordingId)
        val direction = when {
            imageId != null -> CardDirection.RECALL
            audioRecordingId != null -> CardDirection.LISTENING
            else -> CardDirection.RECOGNITION
        }
        srs.addItems(
            listOf(
                NewItem(
                    id, ItemKind.CUSTOM, w, reading?.trim()?.ifEmpty { null }, listOfNotNull(meaning?.trim()?.ifEmpty { null }),
                    listOfNotNull(reading?.trim()?.ifEmpty { null }), ItemSource.USER, listOf(direction),
                    context = json.encodeToString(PersonalCardContext.serializer(), ctx),
                ),
            ),
        )
        audioRecordingId?.let { tagRecording(it, id) }
        return id
    }

    /** Replaces the picture and/or recording of a personal card (null keeps the current one). */
    @Throws(Exception::class)
    suspend fun update(itemId: String, imageId: String? = null, audioRecordingId: String? = null, note: String? = null) {
        val item = srs.item(itemId) ?: return
        val ctx = contextOf(item) ?: return
        val next = ctx.copy(imageId = imageId ?: ctx.imageId, audioRecordingId = audioRecordingId ?: ctx.audioRecordingId, note = note ?: ctx.note)
        srs.addItems(
            listOf(
                NewItem(
                    item.id, item.kind, item.primaryText, item.reading, item.meanings, item.acceptedReadings, item.source,
                    emptyList(), item.level, item.jlpt, context = json.encodeToString(PersonalCardContext.serializer(), next),
                ),
            ),
        )
        if (next.imageId != null) addCard(itemId, CardDirection.RECALL)
        audioRecordingId?.let { tagRecording(it, itemId) }
    }

    /**
     * Gives any item a self-recorded audio side: the recording (already registered with [RecordingStore]) is
     * tagged as the item's card audio and a LISTENING card is added (it awaits a lesson like any new card).
     */
    @Throws(Exception::class)
    suspend fun addAudioSide(itemId: String, recordingId: String): String {
        tagRecording(recordingId, itemId)
        return addCard(itemId, CardDirection.LISTENING)
    }

    /** Rendering data for [cardId], or null when the card or its item doesn't exist. */
    @Throws(Exception::class)
    suspend fun render(cardId: String): PersonalCardRender? {
        val card = srs.card(cardId) ?: return null
        val item = srs.item(card.itemId) ?: return null
        val ctx = contextOf(item)
        val missing = ArrayList<String>()
        val imagePath = ctx?.imageId?.let { id -> images.pathOf(id).also { if (it == null) missing += "picture" } }
        val audioId = ctx?.audioRecordingId
        val audio = (audioId?.let { recordings.recording(it) } ?: recordings.recordingsFor(RecordingKind.CARD, item.id).firstOrNull())
        val audioPath = audio?.let { rec -> if (recordings.fileExists(rec)) recordings.pathOf(rec) else null }
        if (audioPath == null && (audioId != null || card.direction == CardDirection.LISTENING)) missing += "recording"
        val word = ctx?.word ?: item.primaryText
        val meaning = item.meanings.joinToString("; ").ifEmpty { null }
        val note = ctx?.note?.ifEmpty { null } ?: item.myStory.ifEmpty { null }
        val speak = item.reading ?: word
        val full = CardFace(imagePath, audioPath, word, item.reading, listOfNotNull(meaning, note).joinToString("\n").ifEmpty { null }, speak)
        val front = when (card.direction) {
            CardDirection.RECALL -> CardFace(imagePath, null, null, null, null, null)
            CardDirection.LISTENING -> CardFace(null, audioPath, null, null, null, speak)
            else -> CardFace(null, null, word, null, null, null)
        }
        return PersonalCardRender(item.id, card.direction, front, full, missing)
    }

    fun contextOf(item: StudyItem): PersonalCardContext? {
        val raw = item.context ?: return null
        return runCatching { json.decodeFromString(PersonalCardContext.serializer(), raw) }.getOrNull()
            ?.takeIf { it.type == PersonalCardContext.TYPE }
    }

    private suspend fun tagRecording(recordingId: String, itemId: String) = io {
        // A recording made for this card: file it under CARD so it is found (and synced) with the item.
        db.transaction {
            val row = db.mediaQueries.recordingById(recordingId).executeAsOneOrNull() ?: return@transaction
            if (row.kind != RecordingKind.CARD.name || row.ref != itemId) {
                db.mediaQueries.purgeRecording(recordingId)
                db.mediaQueries.insertRecording(
                    row.id, RecordingKind.CARD.name, itemId, row.file_name, row.mime, row.duration_ms, row.size_bytes,
                    row.reference_key, row.origin_device, row.created_at, row.synced_at,
                )
            }
        }
    }

    private suspend fun addCard(itemId: String, direction: CardDirection): String = io {
        val now = clock.now().toEpochMilliseconds()
        val cardId = SrsRepository.cardId(itemId, direction)
        db.srsQueries.insertCardIfAbsent(cardId, itemId, direction.name, now, now, now)
        cardId
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
