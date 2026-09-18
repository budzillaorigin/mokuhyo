package app.tsumugi.media

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.recordings.PendingRecording
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.RecordingStore
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.SrsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** A saved span of a media file with its subtitle line (G-04 "save clip to SRS"). */
data class MediaClip(
    val id: String,
    /** The LISTENING item made from it. */
    val itemId: String,
    val mediaId: String,
    val mediaTitle: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String?,
    /** The cut audio segment, once the platform has written it. */
    val recordingId: String?,
    val createdAt: Long,
)

/**
 * The clip in the LISTENING item's `context` JSON, so the card carries its source on every synced device even
 * where the audio file isn't present (the review then speaks [text] with TTS, rule 20's fallback).
 */
@Serializable
data class ClipContext(
    val type: String = TYPE,
    val clipId: String,
    val mediaId: String,
    val mediaTitle: String,
    val startMs: Long,
    val endMs: Long,
) {
    companion object {
        const val TYPE = "clip"
    }
}

/** A new clip plus the file the platform should cut the audio segment [startMs, endMs) into. */
data class ClipDraft(val clip: MediaClip, val audio: PendingRecording, val startMs: Long, val endMs: Long)

/**
 * "Save clip to SRS" (BRIEF_V2 G-04, DECISIONS D-113): a clip is a media id + start/end + the subtitle line, and
 * becomes a LISTENING card (hear the clip → understand it; the line and translation are the answer). Shared code
 * keeps the metadata and the card; the platform cuts the segment (AVAssetExportSession / Media3 Transformer) into
 * [ClipDraft.audio] and calls [attachAudio].
 */
class ClipService(
    private val db: TsumugiDatabase,
    private val srs: SrsRepository,
    private val recordings: RecordingStore,
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.mediaQueries
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Saves the clip and its LISTENING card (a new card that waits for a lesson like any other). [paddingMs] widens
     * the cut on both sides so the first and last syllables aren't clipped.
     */
    @Throws(Exception::class)
    suspend fun saveClip(
        mediaId: String,
        mediaTitle: String,
        startMs: Long,
        endMs: Long,
        text: String,
        translation: String? = null,
        reading: String? = null,
        paddingMs: Long = DEFAULT_PADDING_MS,
    ): ClipDraft {
        require(endMs > startMs) { "a clip needs end > start" }
        val line = normalizeNfc(text.trim())
        require(line.isNotEmpty()) { "a clip needs its subtitle line" }
        val id = Uuid.random().toString()
        val itemId = "clip:$id"
        val from = (startMs - paddingMs).coerceAtLeast(0)
        val to = endMs + paddingMs
        val now = clock.now().toEpochMilliseconds()
        val ctx = ClipContext(clipId = id, mediaId = mediaId, mediaTitle = mediaTitle, startMs = from, endMs = to)
        srs.addItems(
            listOf(
                NewItem(
                    itemId, ItemKind.LISTENING, line, reading?.trim()?.ifEmpty { null },
                    listOfNotNull(translation?.trim()?.ifEmpty { null }), emptyList(), ItemSource.USER,
                    listOf(CardDirection.LISTENING), context = json.encodeToString(ClipContext.serializer(), ctx),
                ),
            ),
        )
        withContext(Dispatchers.IO) { q.insertClip(id, itemId, mediaId, mediaTitle, from, to, line, translation, now) }
        val pending = recordings.newRecording("m4a")
        return ClipDraft(clip(id)!!, pending, from, to)
    }

    /** Registers the cut segment the platform wrote into [ClipDraft.audio]. */
    @Throws(Exception::class)
    suspend fun attachAudio(clipId: String, audio: PendingRecording, durationMs: Long): MediaClip {
        val rec = recordings.register(audio, RecordingKind.CLIP, clipId, durationMs)
        withContext(Dispatchers.IO) { q.setClipRecording(rec.id, clipId) }
        return clip(clipId)!!
    }

    @Throws(Exception::class)
    suspend fun clip(id: String): MediaClip? = withContext(Dispatchers.IO) { q.clipById(id).executeAsOneOrNull()?.toClip() }

    @Throws(Exception::class)
    suspend fun clipsFor(mediaId: String): List<MediaClip> = withContext(Dispatchers.IO) { q.clipsForMedia(mediaId).executeAsList().map { it.toClip() } }

    /** The clip behind a LISTENING item's context, or null for items that aren't clips. */
    fun contextOf(itemContext: String?): ClipContext? = itemContext?.let {
        runCatching { json.decodeFromString(ClipContext.serializer(), it) }.getOrNull()?.takeIf { c -> c.type == ClipContext.TYPE }
    }

    private fun app.tsumugi.db.Media_clip.toClip() =
        MediaClip(id, item_id, media_id, media_title, start_ms, end_ms, text, translation, recording_id, created_at)

    companion object {
        const val DEFAULT_PADDING_MS = 250L
    }
}
