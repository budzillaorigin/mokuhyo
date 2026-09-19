package app.tsumugi.media

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.recordings.ImageStore
import app.tsumugi.recordings.PendingRecording
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

/** "Mine this line" as a sentence card or a word card (BRIEF_V2 §6.2). */
enum class MineKind { SENTENCE, VOCAB }

/**
 * A mined card's `context` JSON (the Yomikiri / Immersion Kit card: word, sentence with the word highlighted,
 * audio, image). It syncs with the item; the clip audio and the frame are device files that follow only with
 * recordings sync on (D-111), and the card falls back to TTS without them.
 */
@Serializable
data class MinedCardContext(
    val type: String = TYPE,
    val word: String,
    val sentence: String,
    val wordStart: Int,
    val wordEnd: Int,
    val clipId: String? = null,
    val mediaId: String? = null,
    val mediaTitle: String? = null,
    val startMs: Long? = null,
    val endMs: Long? = null,
    /** [app.tsumugi.recordings.UserImage] id of the video frame. */
    val imageId: String? = null,
    /** [app.tsumugi.recordings.Recording] id of the cut clip audio. */
    val audioRecordingId: String? = null,
    /** JMdict id of a VOCAB card's word (also the item's ref_id). */
    val entryId: Long? = null,
) {
    companion object {
        const val TYPE = "mined"
    }
}

/**
 * What the platform does next after [SentenceMiner.mineLine]: cut [startMs, endMs) of the media into [audio] (the
 * ClipService path, AVAssetExportSession / Media3 Transformer), write the frame at [thumbnailMs] into [image] (video
 * only; null for audio), then call [SentenceMiner.attachMedia].
 */
data class MineDraft(
    val itemId: String,
    val clipId: String,
    val audio: PendingRecording,
    val image: PendingRecording?,
    val startMs: Long,
    val endMs: Long,
    val thumbnailMs: Long?,
)

/** A mined card as the review screen draws it: the sentence split around the highlighted word. */
data class MinedCardRender(
    val itemId: String,
    val kind: MineKind,
    val word: String,
    val reading: String?,
    val meanings: List<String>,
    val sentence: String,
    val before: String,
    val highlighted: String,
    val after: String,
    val translation: String?,
    val mediaTitle: String?,
    /** Absolute paths on this device, or null when the file isn't here (recordings sync off on this device). */
    val audioPath: String?,
    val imagePath: String?,
    /** Speak this with TTS when [audioPath] is null. */
    val speakFallback: String,
    val missing: List<String>,
)

/**
 * "Mine this line" (BRIEF_V2 §6.2, DECISIONS D-161): a line from the learner's media becomes a SENTENCE card
 * (RECOGNITION: the sentence with the word highlighted and the frame on the front; audio, translation on the back)
 * or a VOCAB card (MEANING + READING, the sentence as context). Both keep a `media_clip` row and reuse the clip
 * audio / picture infrastructure of Phase 10: the clip is a CLIP recording, the frame a [ImageStore] picture.
 * Mined cards are introduced right away, like reader mining: the learner chose this line.
 */
class SentenceMiner(
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val recordings: RecordingStore,
    private val images: ImageStore,
    private val clock: Clock = Clock.System,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Makes the card and returns the files the platform should fill. [wordStart]/[wordEnd] mark the word in
     * [sentence] (a SENTENCE card may leave them equal to highlight nothing). A VOCAB card needs the word's
     * [meanings]; [entryId] links it to the dictionary.
     */
    @Throws(Exception::class)
    suspend fun mineLine(
        kind: MineKind,
        mediaId: String,
        mediaTitle: String,
        mediaKind: MediaKind,
        startMs: Long,
        endMs: Long,
        sentence: String,
        wordStart: Int,
        wordEnd: Int,
        reading: String? = null,
        meanings: List<String> = emptyList(),
        translation: String? = null,
        entryId: Long? = null,
    ): MineDraft {
        require(endMs > startMs) { "a line needs end > start" }
        val line = normalizeNfc(sentence.trim())
        require(line.isNotEmpty()) { "nothing to mine" }
        val (ws, we) = if (wordStart in 0 until wordEnd && wordEnd <= line.length) wordStart to wordEnd else 0 to 0
        val word = if (we > ws) line.substring(ws, we) else ""
        if (kind == MineKind.VOCAB) require(word.isNotEmpty()) { "a word card needs the word marked in the line" }
        val span = SentenceBank.clipSpan(startMs, endMs, mediaKind)
        val clipId = Uuid.random().toString()
        val itemId = "mine:" + Uuid.random().toString()
        val ctx = MinedCardContext(
            word = word, sentence = line, wordStart = ws, wordEnd = we, clipId = clipId, mediaId = mediaId,
            mediaTitle = mediaTitle, startMs = span.startMs, endMs = span.endMs, entryId = entryId,
        )
        val tr = translation?.trim()?.ifEmpty { null }
        val item = when (kind) {
            MineKind.SENTENCE -> NewItem(
                itemId, ItemKind.SENTENCE, line, null, listOfNotNull(tr), emptyList(), ItemSource.USER,
                listOf(CardDirection.RECOGNITION), context = encode(ctx),
            )
            MineKind.VOCAB -> NewItem(
                itemId, ItemKind.VOCAB, word, reading?.trim()?.ifEmpty { null }, meanings.map { it.trim() }.filter { it.isNotEmpty() },
                listOfNotNull(reading?.trim()?.ifEmpty { null }), ItemSource.USER, listOf(CardDirection.MEANING, CardDirection.READING),
                refId = entryId?.toString(), context = encode(ctx),
            )
        }
        srs.addItems(listOf(item))
        srs.introduce(item.directions.map { SrsRepository.cardId(itemId, it) })
        val now = clock.now().toEpochMilliseconds()
        withContext(Dispatchers.IO) { db.mediaQueries.insertClip(clipId, itemId, mediaId, mediaTitle, span.startMs, span.endMs, line, tr, now) }
        val audio = recordings.newRecording("m4a")
        val image = if (span.thumbnailMs != null) images.newImage("jpg") else null
        return MineDraft(itemId, clipId, audio, image, span.startMs, span.endMs, span.thumbnailMs)
    }

    /**
     * Registers what the platform wrote: the cut audio ([audioDurationMs] > 0) and the frame ([imageWritten]). Either
     * may be missing (extraction failed); the card still works with TTS and no picture.
     */
    @Throws(Exception::class)
    suspend fun attachMedia(draft: MineDraft, audioDurationMs: Long, imageWritten: Boolean): MinedCardRender? {
        val recordingId = if (audioDurationMs > 0) {
            recordings.register(draft.audio, RecordingKind.CLIP, draft.clipId, audioDurationMs).id.also { id ->
                withContext(Dispatchers.IO) { db.mediaQueries.setClipRecording(id, draft.clipId) }
            }
        } else {
            null
        }
        val imageId = if (imageWritten && draft.image != null) images.register(draft.image).id else null
        val item = srs.item(draft.itemId) ?: return null
        val ctx = contextOf(item) ?: return null
        val next = ctx.copy(imageId = imageId ?: ctx.imageId, audioRecordingId = recordingId ?: ctx.audioRecordingId)
        srs.addItems(
            listOf(
                NewItem(
                    item.id, item.kind, item.primaryText, item.reading, item.meanings, item.acceptedReadings, item.source,
                    emptyList(), item.level, item.jlpt, refId = ctx.entryId?.toString(), context = encode(next),
                ),
            ),
        )
        return render(draft.itemId)
    }

    /** Rendering data for a mined item (either card direction), or null for items that aren't mined lines. */
    @Throws(Exception::class)
    suspend fun render(itemId: String): MinedCardRender? {
        val item = srs.item(itemId) ?: return null
        val ctx = contextOf(item) ?: return null
        val missing = ArrayList<String>()
        val imagePath = ctx.imageId?.let { id -> images.pathOf(id).also { if (it == null) missing += "picture" } }
        val audio = ctx.audioRecordingId?.let { recordings.recording(it) }
            ?: ctx.clipId?.let { recordings.recordingsFor(RecordingKind.CLIP, it).firstOrNull() }
        val audioPath = audio?.let { if (recordings.fileExists(it)) recordings.pathOf(it) else null }
        if (audioPath == null) missing += "recording"
        val s = ctx.sentence
        val ws = ctx.wordStart.coerceIn(0, s.length)
        val we = ctx.wordEnd.coerceIn(ws, s.length)
        val kind = if (item.kind == ItemKind.VOCAB) MineKind.VOCAB else MineKind.SENTENCE
        val translation = if (kind == MineKind.SENTENCE) item.meanings.firstOrNull() else null
        return MinedCardRender(
            item.id, kind, ctx.word, item.reading, if (kind == MineKind.VOCAB) item.meanings else emptyList(), s,
            s.substring(0, ws), s.substring(ws, we), s.substring(we), translation, ctx.mediaTitle, audioPath, imagePath,
            speakFallback = s, missing = missing,
        )
    }

    fun contextOf(item: StudyItem): MinedCardContext? = item.context?.let {
        runCatching { json.decodeFromString(MinedCardContext.serializer(), it) }.getOrNull()?.takeIf { c -> c.type == MinedCardContext.TYPE }
    }

    private fun encode(ctx: MinedCardContext) = json.encodeToString(MinedCardContext.serializer(), ctx)
}
