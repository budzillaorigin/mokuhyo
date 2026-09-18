package app.tsumugi.recordings

import app.tsumugi.db.TsumugiDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class UserImage(
    val id: String,
    /** File name inside [ImageStore.dir]. */
    val fileName: String,
    val mime: String,
    val sizeBytes: Long,
    val originDevice: String,
    val createdAt: Long,
    val syncedAt: Long?,
)

/**
 * The learner's own pictures for personal cards (BRIEF_V2 G-12, DECISIONS D-112): files under `<dataDir>/images`,
 * indexed in the device-local `user_image` table. Unlike recordings they stay in OS backups (small and
 * irreplaceable). They reach other devices through the same opt-in blob sync as recordings.
 *
 * The platform's image picker downscales (≤ 1600 px, JPEG) and hands the bytes to [save], or writes into
 * [newImage]'s path and calls [register].
 */
class ImageStore(
    private val db: TsumugiDatabase,
    private val fs: FileSystem,
    dataDir: Path,
    private val deviceId: String,
    private val clock: Clock = Clock.System,
) {
    val dir: Path = dataDir / DIR
    private val q get() = db.mediaQueries

    @Throws(Exception::class)
    suspend fun newImage(extension: String = "jpg"): PendingRecording = io {
        ensureDir()
        val id = Uuid.random().toString()
        val name = "$id.${extension.trimStart('.').lowercase()}"
        PendingRecording(id, name, (dir / name).toString())
    }

    @Throws(Exception::class)
    suspend fun register(pending: PendingRecording): UserImage = io {
        val size = fs.metadataOrNull(dir / pending.fileName)?.size ?: 0L
        require(size > 0) { "image file ${pending.fileName} is missing or empty" }
        insert(pending.id, pending.fileName, RecordingStore.mimeFor(pending.fileName), size, deviceId, clock.now().toEpochMilliseconds(), null)
    }

    @Throws(Exception::class)
    suspend fun save(bytes: ByteArray, extension: String = "jpg"): UserImage {
        val pending = newImage(extension)
        io { fs.write(pending.path.toPath()) { write(bytes) } }
        return register(pending)
    }

    @Throws(Exception::class)
    suspend fun image(id: String): UserImage? = io { q.imageById(id).executeAsOneOrNull()?.takeIf { it.deleted_at == null }?.toImage() }

    fun pathOf(image: UserImage): String = (dir / image.fileName).toString()

    /** Absolute path of an image by id, or null when it isn't on this device (yet). */
    @Throws(Exception::class)
    suspend fun pathOf(id: String): String? {
        val image = image(id) ?: return null
        return if (io { fs.exists(dir / image.fileName) }) pathOf(image) else null
    }

    @Throws(Exception::class)
    suspend fun delete(id: String) = io {
        val row = q.imageById(id).executeAsOneOrNull() ?: return@io
        runCatching { fs.delete(dir / row.file_name, mustExist = false) }
        if (row.synced_at != null) q.tombstoneImage(clock.now().toEpochMilliseconds(), id) else q.purgeImage(id)
    }

    internal fun insert(id: String, fileName: String, mime: String, size: Long, origin: String, createdAt: Long, syncedAt: Long?): UserImage {
        q.insertImage(id, fileName, mime, size, origin, createdAt, syncedAt)
        return q.imageById(id).executeAsOne().toImage()
    }

    internal fun ensureDir() {
        if (!fs.exists(dir)) fs.createDirectories(dir)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val DIR = "images"
    }
}

internal fun app.tsumugi.db.User_image.toImage() = UserImage(id, file_name, mime, size_bytes, origin_device, created_at, synced_at)
