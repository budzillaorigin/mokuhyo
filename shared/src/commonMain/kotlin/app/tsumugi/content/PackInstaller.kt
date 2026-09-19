package app.tsumugi.content

import app.tsumugi.ai.Sha256
import app.tsumugi.platform.PlatformServices
import app.tsumugi.platform.excludeFromBackup
import app.tsumugi.platform.freeBytes as platformFreeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Source
import okio.buffer
import okio.use
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

@Serializable
data class PackManifest(val packs: List<PackFile>)

/** One content pack file. [version] changes whenever the file's content changes (pack version + hash prefix). */
@Serializable
data class PackFile(val file: String, val version: String, val sha256: String, val bytes: Long)

sealed interface PackStatus {
    data class Installed(val version: String) : PackStatus
    /** Neither installed nor shipped in this build: the feature shows an honest empty state (CLAUDE.md rule 9). */
    data object Missing : PackStatus
}

/** A pack being copied or downloaded into place. */
data class PackInstallProgress(val file: String, val bytesDone: Long, val bytesTotal: Long) {
    val fraction: Double get() = if (bytesTotal <= 0) 0.0 else bytesDone.toDouble() / bytesTotal
}

/** Installing a pack failed; the previous copy (if any) is untouched. */
class PackInstallException(message: String) : Exception(message)

/**
 * Makes read-only content packs available (F-16, D-013 revised by D-052):
 * - **In place** where the platform stores bundled packs as plain files (iOS app bundle): nothing is copied; the
 *   driver opens the bundle file read-only. Stale copies from v1 in `dataDir/packs` are deleted.
 * - **Copied** where it can't (Android assets live inside the APK): copied once per bundled version, off any
 *   global lock (one lock per file), after a free-space check, SHA-256-verified against `manifest.json` while
 *   copying, written to a `.part` file and atomically renamed into place. Stale `.part` files are cleaned up.
 * - **Downloaded** packs (later phases) go through [installFrom] with the same checks.
 *
 * [progress] reports the running copy for the UI (CLAUDE.md rule 15).
 */
class PackInstaller(
    private val fs: FileSystem,
    private val packsDir: Path,
    private val openBundled: (String) -> Source?,
    private val bundledPath: (String) -> Path?,
    private val freeBytes: (Path) -> Long? = { null },
    private val onDirCreated: (Path) -> Unit = {},
) {
    constructor(platform: PlatformServices, fs: FileSystem = platform.fileSystem) : this(
        fs = fs,
        packsDir = platform.dataDir / "packs",
        openBundled = platform::openBundled,
        bundledPath = platform::bundledPackPath,
        freeBytes = ::platformFreeBytes,
        onDirCreated = ::excludeFromBackup,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow<PackInstallProgress?>(null)

    /** The copy in progress, or null. */
    val progress: StateFlow<PackInstallProgress?> = _progress.asStateFlow()

    fun installedVersion(file: String): String? {
        val marker = packsDir / "$file.version"
        return if (fs.exists(marker) && fs.exists(packsDir / file)) fs.read(marker) { readUtf8().trim() } else null
    }

    fun bundledManifest(): PackManifest? =
        openBundled(MANIFEST)?.buffer()?.use { json.decodeFromString(it.readUtf8()) }

    /**
     * Ensures [file] is available: in place from the bundle, or copied if the bundled copy is newer than the
     * installed one. Safe to call on every launch and from several places at once: work on one file is serialized,
     * different files don't wait for each other. Throws [PackInstallException] when a needed copy fails (the old
     * copy, if any, is kept and used instead).
     */
    @Throws(Exception::class)
    suspend fun ensureInstalled(file: String): PackStatus = lockFor(file).withLock {
        withContext(Dispatchers.IO) {
            val bundled = bundledManifest()?.packs?.firstOrNull { it.file == file }
            val installed = installedVersion(file)
            when {
                bundled != null && bundledPath(file) != null -> {
                    // Opened in place: a copy from an older build is dead weight.
                    removeInstalled(file)
                    PackStatus.Installed(bundled.version)
                }
                bundled != null && bundled.version != installed -> {
                    try {
                        val source = openBundled(file) ?: throw PackInstallException("manifest lists $file but it is not bundled")
                        install(bundled, source)
                        PackStatus.Installed(bundled.version)
                    } catch (e: PackInstallException) {
                        if (installed != null) PackStatus.Installed(installed) else throw e
                    }
                }
                installed != null -> PackStatus.Installed(installed)
                else -> PackStatus.Missing
            }
        }
    }

    /** Installs a downloaded pack from [source] (verified against [pack]'s size and hash). */
    @Throws(Exception::class)
    suspend fun installFrom(pack: PackFile, source: Source) = lockFor(pack.file).withLock {
        withContext(Dispatchers.IO) { install(pack, source) }
    }

    private suspend fun install(pack: PackFile, source: Source) {
        fs.createDirectories(packsDir)
        onDirCreated(packsDir)
        cleanStaleParts(pack.file)
        val needed = pack.bytes + pack.bytes / 10
        val free = freeBytes(packsDir)
        if (free != null && free < needed) {
            source.close()
            throw PackInstallException(
                "Not enough storage to install ${pack.file}: needs ${mb(needed)} MB, ${mb(free)} MB free.",
            )
        }
        val temp = packsDir / "${pack.file}.${Uuid.random()}$PART"
        try {
            val sha = Sha256()
            var done = 0L
            _progress.value = PackInstallProgress(pack.file, 0, pack.bytes)
            source.buffer().use { src ->
                fs.sink(temp).buffer().use { out ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var lastReport = 0L
                    while (true) {
                        val n = src.read(buffer, 0, buffer.size)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sha.update(buffer, 0, n)
                        done += n
                        if (done - lastReport >= PROGRESS_STEP) {
                            lastReport = done
                            _progress.value = PackInstallProgress(pack.file, done, pack.bytes)
                            coroutineContext.ensureActive()
                        }
                    }
                }
            }
            if (done != pack.bytes) throw PackInstallException("${pack.file}: expected ${pack.bytes} bytes, got $done")
            val actual = sha.hexDigest()
            if (!actual.equals(pack.sha256, ignoreCase = true)) {
                throw PackInstallException("${pack.file}: checksum mismatch (the bundled or downloaded copy is damaged)")
            }
            val target = packsDir / pack.file
            fs.delete(target, mustExist = false)
            // SQLite side files from a previous version must not be applied to the new file.
            SIDE_FILES.forEach { fs.delete(packsDir / "${pack.file}$it", mustExist = false) }
            fs.atomicMove(temp, target)
            fs.write(packsDir / "${pack.file}.version") { writeUtf8(pack.version) }
        } finally {
            fs.delete(temp, mustExist = false)
            _progress.value = null
        }
    }

    /** Leftovers of copies interrupted by a crash or kill (a new copy always uses a fresh name). */
    private fun cleanStaleParts(file: String) {
        fs.listOrNull(packsDir)?.filter { it.name.startsWith("$file.") && it.name.endsWith(PART) }
            ?.forEach { fs.delete(it, mustExist = false) }
    }

    private fun removeInstalled(file: String) {
        if (!fs.exists(packsDir)) return
        cleanStaleParts(file)
        (listOf(file, "$file.version") + SIDE_FILES.map { "$file$it" }).forEach { fs.delete(packsDir / it, mustExist = false) }
    }

    private suspend fun lockFor(file: String): Mutex = locksGuard.withLock { locks.getOrPut(file) { Mutex() } }

    private fun mb(bytes: Long) = (bytes + (1 shl 20) - 1) / (1 shl 20)

    companion object {
        const val MANIFEST = "manifest.json"
        const val DICTIONARY = "dictionary.sqlite"
        const val KANJI_PATH = "kanji-path.sqlite"
        const val GRAMMAR = "grammar.sqlite"
        const val TOKENIZER = "tokenizer.sqlite"
        const val EXAM = "exam.sqlite"
        const val PRACTICE = "practice.sqlite"
        const val READERS = "readers.sqlite"

        /** One install per file per process, however many AppGraph/PackInstaller instances exist. */
        private val locksGuard = Mutex()
        private val locks = HashMap<String, Mutex>()

        private const val PART = ".part"
        private const val COPY_BUFFER = 256 * 1024
        private const val PROGRESS_STEP = 4L shl 20
        private val SIDE_FILES = listOf("-wal", "-shm", "-journal")
    }
}
