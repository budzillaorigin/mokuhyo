package app.tsumugi.content

import app.tsumugi.platform.PlatformServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
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

/**
 * Installs read-only content packs into `dataDir/packs`. Packs bundled with the app are copied on first launch
 * (and again when the bundled version changes). Downloaded packs (later phases) land in the same folder.
 */
class PackInstaller(
    private val platform: PlatformServices,
    private val fs: FileSystem = platform.fileSystem,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val packsDir: Path get() = platform.dataDir / "packs"

    fun installedVersion(file: String): String? {
        val marker = packsDir / "$file.version"
        return if (fs.exists(marker) && fs.exists(packsDir / file)) fs.read(marker) { readUtf8().trim() } else null
    }

    fun bundledManifest(): PackManifest? =
        platform.openBundled(MANIFEST)?.buffer()?.use { json.decodeFromString(it.readUtf8()) }

    /**
     * Ensures [file] is installed, copying the bundled copy if it is newer. Safe to call on every launch and
     * from several places at once: installs are serialized process-wide.
     */
    suspend fun ensureInstalled(file: String): PackStatus = installLock.withLock { withContext(Dispatchers.IO) {
        val installed = installedVersion(file)
        val bundled = bundledManifest()?.packs?.firstOrNull { it.file == file }
        when {
            bundled != null && bundled.version != installed -> {
                copyBundled(bundled)
                PackStatus.Installed(bundled.version)
            }
            installed != null -> PackStatus.Installed(installed)
            else -> PackStatus.Missing
        }
    } }

    private fun copyBundled(pack: PackFile) {
        fs.createDirectories(packsDir)
        val target = packsDir / pack.file
        val temp = packsDir / "${pack.file}.${Uuid.random()}.part"
        val source = platform.openBundled(pack.file) ?: error("manifest lists ${pack.file} but it is not bundled")
        source.use { src -> fs.sink(temp).buffer().use { it.writeAll(src) } }
        fs.delete(target, mustExist = false)
        // SQLite side files from a previous version must not be applied to the new file.
        listOf("-wal", "-shm", "-journal").forEach { fs.delete(packsDir / "${pack.file}$it", mustExist = false) }
        fs.atomicMove(temp, target)
        fs.write(packsDir / "${pack.file}.version") { writeUtf8(pack.version) }
    }

    companion object {
        /** One install at a time per process, however many AppGraph/PackInstaller instances exist. */
        private val installLock = Mutex()

        const val MANIFEST = "manifest.json"
        const val DICTIONARY = "dictionary.sqlite"
        const val KANJI_PATH = "kanji-path.sqlite"
        const val GRAMMAR = "grammar.sqlite"
    }
}
