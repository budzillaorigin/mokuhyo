package app.tsumugi.recordings

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.settings.DeviceSettings
import app.tsumugi.sync.BlobStore
import app.tsumugi.sync.Sealer
import app.tsumugi.sync.SyncException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import kotlin.time.Clock

/** One device's published list of its recordings and pictures, plus every deletion it knows of (D-111). */
@Serializable
data class MediaManifest(
    val version: Int = 1,
    val updatedAt: Long,
    val recordings: List<RecordingEntry> = emptyList(),
    val images: List<ImageEntry> = emptyList(),
    /** Ids of recordings and pictures deleted on this device (any origin): every device deletes its copy. */
    val deleted: List<String> = emptyList(),
)

@Serializable
data class RecordingEntry(
    val id: String, val kind: String, val ref: String? = null, val fileName: String, val mime: String,
    val durationMs: Long, val referenceKey: String? = null, val origin: String, val createdAt: Long,
)

@Serializable
data class ImageEntry(val id: String, val fileName: String, val mime: String, val origin: String, val createdAt: Long)

enum class RecordingSyncPhase { UPLOADING, DELETING, DOWNLOADING, DONE }

data class RecordingSyncProgress(val phase: RecordingSyncPhase, val done: Int, val total: Int)

data class RecordingSyncResult(
    val enabled: Boolean,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deleted: Int = 0,
    /** Per-file problems (quota exceeded, a file over 20 MB, a blob that failed to decrypt); the rest still synced. */
    val failures: List<String> = emptyList(),
)

/**
 * Opt-in sync of recordings and pictures through the sync server's blob store (BRIEF_V2 G-03/G-12, DECISIONS
 * D-111). Off by default; the switch is a per-device setting ([ENABLED_KEY], CLAUDE.md rule 16), so turning it on
 * on the phone doesn't start uploads from the tablet.
 *
 * Protocol (no server changes; the blob store is plain key → bytes):
 * - each file is one blob, `rec-<id>` / `img-<id>`;
 * - each device publishes a manifest blob `man-<server device id>` listing the files it recorded and every
 *   deletion it has seen; devices are discovered through `GET /devices`;
 * - a sync uploads new local files, deletes blobs of deleted files, republishes the manifest, then downloads what
 *   other devices' manifests list and removes what any manifest lists as deleted.
 * With end-to-end encryption on, blob ids are keyed hashes and blob bytes are sealed like rows.
 */
class RecordingSync(
    private val db: TsumugiDatabase,
    private val fs: FileSystem,
    private val recordings: RecordingStore,
    private val images: ImageStore,
    private val deviceSettings: DeviceSettings,
    /** This install's device id (DeviceState), the origin written on local files. */
    private val localDevice: String,
    /** The signed-in sync client, or null when sync isn't set up. */
    private val blobs: suspend () -> BlobStore?,
    /** This device's id on the server (from sign-in), which names its manifest. */
    private val serverDeviceId: () -> String?,
    private val sealer: () -> Sealer? = { null },
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.mediaQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Throws(Exception::class)
    suspend fun isEnabled(): Boolean = deviceSettings.bool(ENABLED_KEY, false)

    @Throws(Exception::class)
    suspend fun setEnabled(on: Boolean) = deviceSettings.put(ENABLED_KEY, on.toString())

    /** One full round (see the class comment). Does nothing and reports `enabled = false` while the switch is off. */
    @Throws(Exception::class)
    suspend fun sync(onProgress: (RecordingSyncProgress) -> Unit = {}): RecordingSyncResult {
        if (!isEnabled()) return RecordingSyncResult(enabled = false)
        val store = blobs() ?: throw SyncException("Sign in to a sync server to sync recordings")
        val me = serverDeviceId() ?: throw SyncException("Sign in again so this device has a server id")
        val failures = ArrayList<String>()

        // 1. Upload new local files.
        val toUpload = io { q.recordingsToUpload(localDevice).executeAsList() to q.imagesToUpload(localDevice).executeAsList() }
        val uploadTotal = toUpload.first.size + toUpload.second.size
        var uploaded = 0
        for (r in toUpload.first) {
            onProgress(RecordingSyncProgress(RecordingSyncPhase.UPLOADING, uploaded, uploadTotal))
            if (upload(store, blobId(REC, r.id), recordings.dir / r.file_name, r.mime, failures)) {
                io { q.markRecordingSynced(now(), r.id) }
                uploaded++
            }
        }
        for (i in toUpload.second) {
            onProgress(RecordingSyncProgress(RecordingSyncPhase.UPLOADING, uploaded, uploadTotal))
            if (upload(store, blobId(IMG, i.id), images.dir / i.file_name, i.mime, failures)) {
                io { q.markImageSynced(now(), i.id) }
                uploaded++
            }
        }

        // 2. Deleted files: remove their blobs (idempotent; any device may do it).
        val deadRecordings = io { q.tombstonedRecordings().executeAsList().map { it.id } }
        val deadImages = io { q.tombstonedImages().executeAsList().map { it.id } }
        var deleted = 0
        (deadRecordings.map { REC to it } + deadImages.map { IMG to it }).forEachIndexed { n, (prefix, id) ->
            onProgress(RecordingSyncProgress(RecordingSyncPhase.DELETING, n, deadRecordings.size + deadImages.size))
            runCatching { store.deleteBlob(blobId(prefix, id)) }.onFailure { rethrowCancellation(it) }
        }

        // 3. Publish this device's manifest.
        val manifest = io {
            MediaManifest(
                updatedAt = now(),
                recordings = q.recordingsOfDevice(localDevice).executeAsList()
                    .filter { it.deleted_at == null && it.synced_at != null }
                    .map { RecordingEntry(it.id, it.kind, it.ref, it.file_name, it.mime, it.duration_ms, it.reference_key, it.origin_device, it.created_at) },
                images = q.imagesOfDevice(localDevice).executeAsList()
                    .filter { it.deleted_at == null && it.synced_at != null }
                    .map { ImageEntry(it.id, it.file_name, it.mime, it.origin_device, it.created_at) },
                deleted = (deadRecordings + deadImages).distinct().sorted(),
            )
        }
        putSealed(store, blobId(MAN, me), json.encodeToString(MediaManifest.serializer(), manifest).encodeToByteArray(), "application/json")

        // 4. Read the other devices' manifests.
        val others = store.deviceIds().filter { it != me }.mapNotNull { device ->
            val bytes = getSealed(store, blobId(MAN, device), failures) ?: return@mapNotNull null
            runCatching { json.decodeFromString(MediaManifest.serializer(), bytes.decodeToString()) }.getOrNull()
        }
        val deletedAnywhere = others.flatMap { it.deleted }.toSet()

        // 5. Apply deletions made elsewhere.
        for (id in deletedAnywhere) {
            val r = io { q.recordingById(id).executeAsOneOrNull() }
            if (r != null && r.deleted_at == null) {
                io { runCatching { fs.delete(recordings.dir / r.file_name, mustExist = false) }; q.tombstoneRecording(now(), id) }
                deleted++
            }
            val img = io { q.imageById(id).executeAsOneOrNull() }
            if (img != null && img.deleted_at == null) {
                io { runCatching { fs.delete(images.dir / img.file_name, mustExist = false) }; q.tombstoneImage(now(), id) }
                deleted++
            }
        }

        // 6. Download what's new.
        val wantedRecordings = others.flatMap { it.recordings }.distinctBy { it.id }
            .filter { it.id !in deletedAnywhere && io { q.recordingById(it.id).executeAsOneOrNull() } == null }
        val wantedImages = others.flatMap { it.images }.distinctBy { it.id }
            .filter { it.id !in deletedAnywhere && io { q.imageById(it.id).executeAsOneOrNull() } == null }
        val downloadTotal = wantedRecordings.size + wantedImages.size
        var downloaded = 0
        for (e in wantedRecordings) {
            onProgress(RecordingSyncProgress(RecordingSyncPhase.DOWNLOADING, downloaded, downloadTotal))
            val bytes = getSealed(store, blobId(REC, e.id), failures) ?: continue
            io {
                recordings.ensureDir()
                fs.write(recordings.dir / safeName(e.fileName, e.id)) { write(bytes) }
                val kind = runCatching { RecordingKind.valueOf(e.kind) }.getOrDefault(RecordingKind.FREE)
                recordings.insert(e.id, kind, e.ref, safeName(e.fileName, e.id), e.mime, e.durationMs, bytes.size.toLong(), e.referenceKey, e.origin, e.createdAt, now())
            }
            downloaded++
        }
        for (e in wantedImages) {
            onProgress(RecordingSyncProgress(RecordingSyncPhase.DOWNLOADING, downloaded, downloadTotal))
            val bytes = getSealed(store, blobId(IMG, e.id), failures) ?: continue
            io {
                images.ensureDir()
                fs.write(images.dir / safeName(e.fileName, e.id)) { write(bytes) }
                images.insert(e.id, safeName(e.fileName, e.id), e.mime, bytes.size.toLong(), e.origin, e.createdAt, now())
            }
            downloaded++
        }
        onProgress(RecordingSyncProgress(RecordingSyncPhase.DONE, downloadTotal, downloadTotal))
        return RecordingSyncResult(true, uploaded, downloaded, deleted, failures)
    }

    /** The blob id for a plain name: as is, or a keyed hash with end-to-end encryption on (so ids reveal nothing). */
    fun blobId(prefix: String, id: String): String {
        val plain = "$prefix-$id"
        return sealer()?.let { "e-" + it.keyId(BLOB_TABLE, plain) } ?: plain
    }

    private suspend fun upload(store: BlobStore, id: String, file: okio.Path, mime: String, failures: MutableList<String>): Boolean {
        val bytes = io { if (fs.exists(file)) fs.read(file) { readByteArray() } else null }
        if (bytes == null) {
            failures += "${file.name}: file is missing"
            return false
        }
        return try {
            putSealed(store, id, bytes, mime)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "${file.name}: ${e.message}"
            false
        }
    }

    private suspend fun putSealed(store: BlobStore, id: String, bytes: ByteArray, mime: String) {
        val s = sealer()
        store.putBlob(id, if (s == null) mime else "application/octet-stream", s?.sealBytes(bytes) ?: bytes)
    }

    private suspend fun getSealed(store: BlobStore, id: String, failures: MutableList<String>): ByteArray? {
        val raw = try {
            store.getBlob(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures += "$id: ${e.message}"
            null
        } ?: return null
        val s = sealer() ?: return raw
        return s.openBytes(raw) ?: run {
            failures += "$id: couldn't decrypt (wrong passphrase?)"
            null
        }
    }

    /** A remote file name is only trusted if it is a plain name; otherwise the id is used (no path traversal). */
    private fun safeName(name: String, id: String): String =
        if (SAFE_NAME.matches(name)) name else id + "." + name.substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(8)

    private fun rethrowCancellation(t: Throwable) {
        if (t is CancellationException) throw t
    }

    private fun now() = clock.now().toEpochMilliseconds()

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Device setting (never synced): "true" to sync recordings and pictures. Default off. */
        const val ENABLED_KEY = "sync.recordings"
        const val REC = "rec"
        const val IMG = "img"
        const val MAN = "man"
        private const val BLOB_TABLE = "blob"
        private val SAFE_NAME = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
