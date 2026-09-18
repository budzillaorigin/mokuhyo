package app.tsumugi.recordings

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.platform.excludeFromBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** What a recording belongs to (BRIEF_V2 G-03). [ref] on [Recording] names the thing itself. */
enum class RecordingKind {
    /** An SRS item (ref = item id): shadowing a word, or a self-recorded audio card side. */
    ITEM,
    /** A sentence (ref = the sentence text, NFC). */
    SENTENCE,
    /** A conversation turn (ref = "<session id>/<turn index>"). */
    TURN,
    /** The audio side of a personal card (ref = item id), see [app.tsumugi.cards.PersonalCards]. */
    CARD,
    /** A media clip's cut audio (ref = clip id), see [app.tsumugi.media.ClipService]. */
    CLIP,
    /** Anything else (ref may be null). */
    FREE,
}

data class Recording(
    val id: String,
    val kind: RecordingKind,
    val ref: String?,
    /** File name inside [RecordingStore.dir]. */
    val fileName: String,
    val mime: String,
    val durationMs: Long,
    val sizeBytes: Long,
    /** The model audio this recording is compared with; see [ReferenceClip]. */
    val referenceKey: String?,
    val originDevice: String,
    val createdAt: Long,
    /** When it was uploaded by the opt-in recordings sync; null = not uploaded. */
    val syncedAt: Long?,
)

/** Where a reference clip comes from; the platform resolves each kind to playable audio. */
enum class ReferenceKind {
    /** Pre-rendered audio pack entry (rule 20): value = the pack's audio key. */
    PACK_AUDIO,
    /** A saved media clip: value = clip id. Its audio is a [Recording] of kind [RecordingKind.CLIP]. */
    CLIP,
    /** Another recording (e.g. a native speaker's): value = recording id. */
    RECORDING,
    /** No stored audio: speak value (the text) with the system/VOICEVOX voice. */
    TTS,
}

/** A side-by-side reference, parsed from [Recording.referenceKey] ("pack:…", "clip:…", "rec:…", "tts:…"). */
data class ReferenceClip(val key: String, val kind: ReferenceKind, val value: String) {
    companion object {
        fun pack(audioKey: String) = ReferenceClip("pack:$audioKey", ReferenceKind.PACK_AUDIO, audioKey)
        fun clip(clipId: String) = ReferenceClip("clip:$clipId", ReferenceKind.CLIP, clipId)
        fun recording(id: String) = ReferenceClip("rec:$id", ReferenceKind.RECORDING, id)
        fun tts(text: String) = ReferenceClip("tts:$text", ReferenceKind.TTS, text)

        fun parse(key: String): ReferenceClip? {
            val prefix = key.substringBefore(':', "")
            val value = key.substringAfter(':', "")
            if (value.isEmpty()) return null
            return when (prefix) {
                "pack" -> pack(value)
                "clip" -> clip(value)
                "rec" -> recording(value)
                "tts" -> tts(value)
                else -> null
            }
        }
    }
}

/**
 * Side-by-side playback metadata: the model audio and the learner's attempt, for an "A/B" player (G-03). The
 * platform plays [reference] (resolving its kind: audio pack file, clip recording, other recording, or TTS of the
 * text) and then [learnerPath].
 */
data class SideBySide(val reference: ReferenceClip?, val learner: Recording, val learnerPath: String, val referencePath: String?)

/** A file path the platform records into, then passes back to [RecordingStore.register]. */
data class PendingRecording(val id: String, val fileName: String, val path: String)

/**
 * The learner's recordings (BRIEF_V2 G-03, DECISIONS D-110): files under `<dataDir>/recordings` (excluded from
 * iOS backups), indexed in the device-local `recording` table. Nothing here syncs; the opt-in [RecordingSync]
 * uploads files to the sync server's blob store when the learner turns it on.
 *
 * Flow: [newRecording] gives a path → the platform records (m4a/wav) into it → [register] with the duration. Or
 * [save] for audio already in memory (a WAV built in shared code).
 */
class RecordingStore(
    private val db: TsumugiDatabase,
    private val fs: FileSystem,
    dataDir: Path,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
) {
    val dir: Path = dataDir / DIR
    private val q get() = db.mediaQueries

    /** A new id and the file path to record into (the directory exists afterwards). */
    @Throws(Exception::class)
    suspend fun newRecording(extension: String = "m4a"): PendingRecording = io {
        ensureDir()
        val id = Uuid.random().toString()
        val name = "$id.${extension.trimStart('.').lowercase()}"
        PendingRecording(id, name, (dir / name).toString())
    }

    /** Indexes a file the platform wrote at [pending].path. Throws when the file is missing or empty. */
    @Throws(Exception::class)
    suspend fun register(
        pending: PendingRecording,
        kind: RecordingKind,
        ref: String?,
        durationMs: Long,
        mime: String = mimeFor(pending.fileName),
        referenceKey: String? = null,
    ): Recording = io {
        val size = fs.metadataOrNull(dir / pending.fileName)?.size ?: 0L
        require(size > 0) { "recording file ${pending.fileName} is missing or empty" }
        insert(pending.id, kind, ref, pending.fileName, mime, durationMs, size, referenceKey, deviceId, clock.now().toEpochMilliseconds(), null)
    }

    /** Writes [bytes] as a new recording (e.g. a WAV assembled in shared code) and indexes it. */
    @Throws(Exception::class)
    suspend fun save(
        bytes: ByteArray,
        kind: RecordingKind,
        ref: String?,
        durationMs: Long,
        extension: String = "wav",
        referenceKey: String? = null,
    ): Recording {
        val pending = newRecording(extension)
        io { fs.write(pending.path.toPath()) { write(bytes) } }
        return register(pending, kind, ref, durationMs, mimeFor(pending.fileName), referenceKey)
    }

    @Throws(Exception::class)
    suspend fun recording(id: String): Recording? = io { q.recordingById(id).executeAsOneOrNull()?.takeIf { it.deleted_at == null }?.toRecording() }

    /** Recordings of one thing, newest first. */
    @Throws(Exception::class)
    suspend fun recordingsFor(kind: RecordingKind, ref: String): List<Recording> = io { q.recordingsFor(kind.name, ref).executeAsList().map { it.toRecording() } }

    @Throws(Exception::class)
    suspend fun all(): List<Recording> = io { q.allRecordings().executeAsList().map { it.toRecording() } }

    /** Absolute path of a recording's file. */
    fun pathOf(recording: Recording): String = (dir / recording.fileName).toString()

    /** Whether the recording's file is on this device (a synced row may point at a file not downloaded yet). */
    @Throws(Exception::class)
    suspend fun fileExists(recording: Recording): Boolean = io { fs.exists(dir / recording.fileName) }

    /** Total bytes of live recordings (for the storage line in settings). */
    @Throws(Exception::class)
    suspend fun totalBytes(): Long = all().sumOf { it.sizeBytes }

    @Throws(Exception::class)
    suspend fun setReference(id: String, reference: ReferenceClip?) = io { q.setRecordingReference(reference?.key, id) }

    /**
     * Model audio plus the learner's attempt for side-by-side playback, or null for an unknown id. Clip and
     * recording references resolve to a file path here; pack audio and TTS are resolved by the platform.
     */
    @Throws(Exception::class)
    suspend fun sideBySide(id: String): SideBySide? {
        val rec = recording(id) ?: return null
        val reference = rec.referenceKey?.let(ReferenceClip::parse)
        val referencePath = when (reference?.kind) {
            ReferenceKind.RECORDING -> recording(reference.value)?.let(::pathOf)
            ReferenceKind.CLIP -> recordingsFor(RecordingKind.CLIP, reference.value).firstOrNull()?.let(::pathOf)
            else -> null
        }
        return SideBySide(reference, rec, pathOf(rec), referencePath)
    }

    /**
     * Deletes the file. A recording that was uploaded keeps a tombstone row so [RecordingSync] can delete the blob
     * and tell the other devices; one that never left the device is removed outright.
     */
    @Throws(Exception::class)
    suspend fun delete(id: String) = io {
        val row = q.recordingById(id).executeAsOneOrNull() ?: return@io
        runCatching { fs.delete(dir / row.file_name, mustExist = false) }
        if (row.synced_at != null) q.tombstoneRecording(clock.now().toEpochMilliseconds(), id) else q.purgeRecording(id)
    }

    // --- For RecordingSync -----------------------------------------------------------------------------------

    internal fun insert(
        id: String, kind: RecordingKind, ref: String?, fileName: String, mime: String, durationMs: Long, size: Long,
        referenceKey: String?, origin: String, createdAt: Long, syncedAt: Long?,
    ): Recording {
        q.insertRecording(id, kind.name, ref, fileName, mime, durationMs, size, referenceKey, origin, createdAt, syncedAt)
        return q.recordingById(id).executeAsOne().toRecording()
    }

    internal fun ensureDir() {
        if (!fs.exists(dir)) {
            fs.createDirectories(dir)
            excludeFromBackup(dir)
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DIR = "recordings"

        fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
            "wav" -> "audio/wav"
            "m4a", "mp4", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            "caf" -> "audio/x-caf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "heic" -> "image/heic"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
    }
}

internal fun app.tsumugi.db.Recording.toRecording() = Recording(
    id, runCatching { RecordingKind.valueOf(kind) }.getOrDefault(RecordingKind.FREE), ref, file_name, mime, duration_ms,
    size_bytes, reference_key, origin_device, created_at, synced_at,
)
