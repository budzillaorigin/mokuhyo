package app.tsumugi.audio

import app.tsumugi.ai.Sha256
import app.tsumugi.content.PackFile
import app.tsumugi.content.PackInstallException
import app.tsumugi.content.PackInstaller
import app.tsumugi.net.NetTimeouts
import app.tsumugi.net.tsumugiHttpClient
import app.tsumugi.platform.PlatformServices
import app.tsumugi.platform.excludeFromBackup
import app.tsumugi.platform.freeBytes as platformFreeBytes
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.buffer
import okio.openZip
import okio.use
import kotlin.coroutines.coroutineContext
import kotlin.uuid.Uuid

/**
 * Installed pre-rendered audio packs (BRIEF_V2 §5.6, CLAUDE.md rule 20, D-095).
 *
 * An `audio-<set>.zip` is **extracted on install** into `dataDir/audio/<set>/` (Application Support on iOS,
 * excluded from backup: it can be downloaded again). Each clip lands at a path derived from its key
 * ([AudioKeys.relativePath]), so [clip] is a single file-exists check, with no index to parse, and the platform
 * players get a plain file path (AVAudioPlayer / MediaPlayer can't read zip entries).
 *
 * Installs are atomic: the archive is verified (SHA-256 + size against the manifest entry when there is one, via
 * [PackInstaller.installFrom]; structure and every entry's size always), extracted into a `.part` folder, and
 * swapped in with a rename. A failed or cancelled install leaves the previous version untouched. Sources:
 * - [download]: a static file host at a URL the learner types in Settings; there is no default URL (D-096).
 * - [installFrom] / [installFile]: a file picked in Files / the Android document picker.
 * - [ensureBundled]: sets shipped inside the app, if a build bundles any (D-097).
 *
 * When a clip is missing, callers fall back to system TTS (rule 20), except the pitch test, which is hidden
 * without its pack (§6.7).
 */
class AudioPackRepository(
    private val fs: FileSystem,
    private val audioDir: Path,
    private val openBundled: (String) -> Source? = { null },
    private val httpEngine: (() -> HttpClientEngine)? = null,
    private val freeBytes: (Path) -> Long? = { null },
    private val onDirCreated: (Path) -> Unit = {},
) {
    constructor(platform: PlatformServices) : this(
        fs = platform.fileSystem,
        audioDir = platform.dataDir / "audio",
        openBundled = platform::openBundled,
        httpEngine = platform::httpEngine,
        freeBytes = ::platformFreeBytes,
        onDirCreated = ::excludeFromBackup,
    )

    private val incomingDir = audioDir / INCOMING
    /** Verified, atomic, space-checked copies of archives whose hash is known (downloaded-pack support, D-052). */
    private val incoming = PackInstaller(fs, incomingDir, { null }, { null }, freeBytes, onDirCreated)
    private val installLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _progress = MutableStateFlow<AudioInstallProgress?>(null)

    /** The install in progress, or null. */
    val progress: StateFlow<AudioInstallProgress?> = _progress.asStateFlow()

    // ------------------------------------------------------------------------------------------------ lookups

    /** The pre-rendered clip for [key] ([AudioKeys]), or null: then play system TTS instead (rule 20). */
    fun clip(key: String): Path? {
        val relative = AudioKeys.relativePath(key) ?: return null
        // A dialogue key lives in the dialogues pack, or in the tracks pack for a track dialogue (D-240).
        return AudioSet.candidatesOf(key).firstNotNullOfOrNull { set ->
            (setDir(set) / relative).takeIf { fs.exists(it) }
        }
    }

    fun isInstalled(set: AudioSet): Boolean = fs.exists(setDir(set) / META)

    /** Every installed set with its version, size and required credits. */
    fun installed(): List<InstalledAudioPack> = AudioSet.entries.mapNotNull(::installedPack)

    fun installedPack(set: AudioSet): InstalledAudioPack? {
        val meta = setDir(set) / META
        if (!fs.exists(meta)) return null
        val m = runCatching { json.decodeFromString(InstalledMeta.serializer(), fs.read(meta) { readUtf8() }) }
            .getOrNull() ?: return null
        return InstalledAudioPack(set, m.version, m.clips, m.bytes, m.credits)
    }

    /** Credit lines owed for the audio installed on this device (the Licenses screen lists them all anyway). */
    fun credits(): List<String> = installed().flatMap { it.credits }.distinct()

    /** The pack's full index (clip metadata: voice, text, duration). Parsed off the main thread. */
    suspend fun index(set: AudioSet): AudioPackIndex? = withContext(Dispatchers.IO) {
        val path = setDir(set) / INDEX
        if (!fs.exists(path)) null else json.decodeFromString(AudioPackIndex.serializer(), fs.read(path) { readUtf8() })
    }

    /** Pitch-accent test items (§6.7); empty when the pitch pack isn't installed, and the test stays hidden. */
    suspend fun pitchItems(): List<PitchTestItem> = withContext(Dispatchers.IO) {
        val path = setDir(AudioSet.PITCH) / ITEMS
        if (!fs.exists(path)) return@withContext emptyList()
        json.decodeFromString(PitchTestItems.serializer(), fs.read(path) { readUtf8() }).items
            .filter { clip(AudioKeys.pitch(it.id)) != null }
    }

    // ------------------------------------------------------------------------------------------------ installs

    /** Reads `audio-manifest.json` from [baseUrl] (a folder URL, or the manifest's own URL). */
    @Throws(Exception::class)
    suspend fun fetchManifest(baseUrl: String): AudioPackManifest {
        val engine = httpEngine ?: throw PackInstallException("No network client")
        val http = tsumugiHttpClient(engine(), NetTimeouts.API)
        try {
            val response = http.get(manifestUrl(baseUrl))
            if (!response.status.isSuccess()) {
                throw PackInstallException("Audio pack list: HTTP ${response.status.value} from ${manifestUrl(baseUrl)}")
            }
            return json.decodeFromString(AudioPackManifest.serializer(), response.bodyAsText())
        } finally {
            http.close()
        }
    }

    /**
     * Downloads [entry] from [baseUrl] (the folder holding `audio-manifest.json`), verifies its size and SHA-256
     * while streaming, and installs it. Cancelling the caller's coroutine stops the download and keeps the old set.
     */
    @Throws(Exception::class)
    suspend fun download(baseUrl: String, entry: AudioPackEntry): InstalledAudioPack = installLock.withLock {
        withContext(Dispatchers.IO) {
            val engine = httpEngine ?: throw PackInstallException("No network client")
            prepareIncoming(entry.bytes * 2 + entry.bytes / 10) // archive + extracted copy
            val part = incomingDir / "${entry.file}.${Uuid.random()}$PART"
            try {
                val sha = Sha256()
                var done = 0L
                _progress.value = AudioInstallProgress(entry.set, AudioInstallProgress.Phase.DOWNLOADING, 0, entry.bytes)
                val http = tsumugiHttpClient(engine(), NetTimeouts.DOWNLOAD)
                try {
                    http.prepareGet(fileUrl(baseUrl, entry.file)).execute { response ->
                        if (!response.status.isSuccess()) {
                            throw PackInstallException("${entry.file}: HTTP ${response.status.value}")
                        }
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(COPY_BUFFER)
                        var lastReport = 0L
                        fs.sink(part).buffer().use { out ->
                            while (true) {
                                val n = channel.readAvailable(buffer, 0, buffer.size)
                                if (n < 0) break
                                if (n == 0) continue
                                out.write(buffer, 0, n)
                                sha.update(buffer, 0, n)
                                done += n
                                if (done > entry.bytes) throw PackInstallException("${entry.file}: larger than expected")
                                if (done - lastReport >= PROGRESS_STEP) {
                                    lastReport = done
                                    _progress.value = AudioInstallProgress(
                                        entry.set, AudioInstallProgress.Phase.DOWNLOADING, done, entry.bytes,
                                    )
                                    coroutineContext.ensureActive()
                                }
                            }
                        }
                    }
                } finally {
                    http.close()
                }
                if (done != entry.bytes) throw PackInstallException("${entry.file}: expected ${entry.bytes} bytes, got $done")
                if (!sha.hexDigest().equals(entry.sha256, ignoreCase = true)) {
                    throw PackInstallException("${entry.file}: checksum mismatch, the download was discarded")
                }
                extract(part, entry.version, AudioSet.fromId(entry.set))
            } finally {
                fs.delete(part, mustExist = false)
                _progress.value = null
            }
        }
    }

    /**
     * Installs an archive from [source] (a picked file, or a bundled copy). With [expected] (a manifest entry) the
     * bytes are verified against its size and SHA-256 through [PackInstaller.installFrom]; without one, the
     * archive's structure and every clip's size are still checked before anything replaces the installed set.
     */
    @Throws(Exception::class)
    suspend fun installFrom(source: Source, expected: AudioPackEntry? = null): InstalledAudioPack = installLock.withLock {
        withContext(Dispatchers.IO) { installLocked(source, expected) }
    }

    /** [installFrom] for a file on disk (e.g. a document the picker copied into the app's inbox). */
    @Throws(Exception::class)
    suspend fun installFile(path: Path, expected: AudioPackEntry? = null): InstalledAudioPack =
        installFrom(fs.source(path), expected)

    /**
     * Installs or updates any audio set the app bundles (`packs/audio-manifest.json` + `packs/audio-<set>.zip`).
     * A no-op when the build bundles none. Safe to call on every launch.
     */
    @Throws(Exception::class)
    suspend fun ensureBundled(): List<InstalledAudioPack> = installLock.withLock {
        withContext(Dispatchers.IO) {
            val manifest = openBundled(MANIFEST)?.buffer()?.use {
                json.decodeFromString(AudioPackManifest.serializer(), it.readUtf8())
            } ?: return@withContext emptyList()
            manifest.packs.mapNotNull { entry ->
                val set = AudioSet.fromId(entry.set) ?: return@mapNotNull null
                if (installedPack(set)?.version == entry.version) return@mapNotNull null
                val source = openBundled(entry.file) ?: return@mapNotNull null
                installLocked(source, entry)
            }
        }
    }

    /** Deletes an installed set; its clips fall back to system TTS. */
    suspend fun remove(set: AudioSet) = installLock.withLock {
        withContext(Dispatchers.IO) { fs.deleteRecursively(setDir(set), mustExist = false) }
    }

    private suspend fun installLocked(source: Source, expected: AudioPackEntry?): InstalledAudioPack {
        val setName = expected?.set ?: "audio"
        if (expected != null) {
            prepareIncoming(expected.bytes + expected.bytes / 10) // the extracted copy; PackInstaller checks its own
            val archive = incomingDir / expected.file
            try {
                coroutineScope {
                    val relay = launch {
                        incoming.progress.collect { p ->
                            if (p != null) {
                                _progress.value = AudioInstallProgress(
                                    setName, AudioInstallProgress.Phase.COPYING, p.bytesDone, p.bytesTotal,
                                )
                            }
                        }
                    }
                    try {
                        incoming.installFrom(PackFile(expected.file, expected.version, expected.sha256, expected.bytes), source)
                    } finally {
                        relay.cancel()
                    }
                }
                return extract(archive, expected.version, AudioSet.fromId(expected.set))
            } finally {
                fs.delete(archive, mustExist = false)
                fs.delete(incomingDir / "${expected.file}.version", mustExist = false)
                _progress.value = null
            }
        }
        prepareIncoming(0)
        val part = incomingDir / "picked.${Uuid.random()}$PART"
        try {
            val sha = Sha256()
            var done = 0L
            source.buffer().use { src ->
                fs.sink(part).buffer().use { out ->
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
                            _progress.value = AudioInstallProgress(setName, AudioInstallProgress.Phase.COPYING, done, -1)
                            coroutineContext.ensureActive()
                        }
                    }
                }
            }
            return extract(part, "sha256:${sha.hexDigest().take(12)}", null)
        } finally {
            fs.delete(part, mustExist = false)
            _progress.value = null
        }
    }

    /**
     * Unpacks a verified archive into `<set>.<uuid>.part/`, checks it, then swaps it in for the installed set.
     * Every clip is written at its key's path; every entry's size must match the index.
     */
    private suspend fun extract(archive: Path, version: String, expectedSet: AudioSet?): InstalledAudioPack {
        val zip = try {
            fs.openZip(archive)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PackInstallException("Not an audio pack (unreadable zip): ${e.message}")
        }
        val root = "/".toPath()
        val index = try {
            json.decodeFromString(AudioPackIndex.serializer(), zip.read(root / INDEX) { readUtf8() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PackInstallException("Not an audio pack (no readable index.json): ${e.message}")
        }
        if (index.format > FORMAT) throw PackInstallException("This audio pack needs a newer version of the app")
        val set = AudioSet.fromId(index.set) ?: throw PackInstallException("Unknown audio set \"${index.set}\"")
        if (expectedSet != null && expectedSet != set) {
            throw PackInstallException("Expected the ${expectedSet.id} audio pack, got ${set.id}")
        }
        val targets = index.clips.map { (key, info) ->
            if (!set.owns(key)) throw PackInstallException("Clip $key doesn't belong to the ${set.id} set")
            val relative = AudioKeys.relativePath(key) ?: throw PackInstallException("Malformed clip key \"$key\"")
            Triple(relative, info, key)
        }
        val total = index.clips.values.sumOf { it.bytes }
        val free = freeBytes(audioDir)
        if (free != null && free < total + total / 10) {
            throw PackInstallException("Not enough storage for the ${set.id} audio: needs ${mb(total)} MB, ${mb(free)} MB free.")
        }
        val staging = audioDir / "${set.id}.${Uuid.random()}$PART"
        try {
            fs.createDirectories(staging)
            var done = 0L
            var lastReport = 0L
            _progress.value = AudioInstallProgress(set.id, AudioInstallProgress.Phase.EXTRACTING, 0, total)
            val buffer = ByteArray(COPY_BUFFER)
            for ((relative, info, key) in targets) {
                val entry = root / info.file
                val target = staging / relative
                target.parent?.let { fs.createDirectories(it) }
                val written = try {
                    copy(zip.source(entry), target, buffer)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw PackInstallException("Audio pack is damaged: $key (${info.file}): ${e.message}")
                }
                if (written != info.bytes) {
                    throw PackInstallException("Audio pack is damaged: $key is $written bytes, the index says ${info.bytes}")
                }
                done += written
                if (done - lastReport >= PROGRESS_STEP) {
                    lastReport = done
                    _progress.value = AudioInstallProgress(set.id, AudioInstallProgress.Phase.EXTRACTING, done, total)
                    coroutineContext.ensureActive()
                }
            }
            copy(zip.source(root / INDEX), staging / INDEX, buffer)
            if (zip.exists(root / ITEMS)) copy(zip.source(root / ITEMS), staging / ITEMS, buffer)
            val meta = InstalledMeta(set.id, version, index.clips.size, done, index.credits)
            fs.write(staging / META) { writeUtf8(json.encodeToString(InstalledMeta.serializer(), meta)) }
            coroutineContext.ensureActive()

            val live = setDir(set)
            val old = audioDir / "${set.id}.${Uuid.random()}$OLD"
            if (fs.exists(live)) fs.atomicMove(live, old)
            fs.atomicMove(staging, live)
            fs.deleteRecursively(old, mustExist = false)
            return InstalledAudioPack(set, version, index.clips.size, done, index.credits)
        } finally {
            fs.deleteRecursively(staging, mustExist = false)
        }
    }

    private fun copy(source: Source, target: Path, buffer: ByteArray): Long {
        var n = 0L
        source.buffer().use { src ->
            fs.sink(target).buffer().use { out ->
                while (true) {
                    val read = src.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    n += read
                }
            }
        }
        return n
    }

    /** Creates the folders (excluded from backup), removes leftovers of interrupted installs, checks space. */
    private fun prepareIncoming(needed: Long) {
        fs.createDirectories(incomingDir)
        onDirCreated(audioDir)
        onDirCreated(incomingDir)
        fs.listOrNull(incomingDir)?.filter { it.name.endsWith(PART) }?.forEach { fs.delete(it, mustExist = false) }
        fs.listOrNull(audioDir)?.filter { it.name.endsWith(PART) || it.name.endsWith(OLD) }
            ?.forEach { fs.deleteRecursively(it, mustExist = false) }
        val free = freeBytes(audioDir)
        if (needed > 0 && free != null && free < needed) {
            throw PackInstallException("Not enough storage for this audio pack: needs ${mb(needed)} MB, ${mb(free)} MB free.")
        }
    }

    private fun setDir(set: AudioSet): Path = audioDir / set.id

    private fun mb(bytes: Long) = (bytes + (1 shl 20) - 1) / (1 shl 20)

    @Serializable
    private data class InstalledMeta(
        val set: String,
        val version: String,
        val clips: Int,
        val bytes: Long,
        val credits: List<String> = emptyList(),
    )

    companion object {
        /** Highest `index.json` format this build understands. */
        const val FORMAT = 1
        const val MANIFEST = "audio-manifest.json"
        private const val INDEX = "index.json"
        private const val ITEMS = "items.json"
        private const val META = "pack.json"
        private const val INCOMING = "incoming"
        private const val PART = ".part"
        private const val OLD = ".old"
        private const val COPY_BUFFER = 256 * 1024
        private const val PROGRESS_STEP = 1L shl 20

        /** `https://host/audio/` or `https://host/audio/audio-manifest.json` → the manifest URL. */
        fun manifestUrl(base: String): String {
            val trimmed = base.trim()
            return if (trimmed.endsWith(".json")) trimmed else trimmed.trimEnd('/') + "/" + MANIFEST
        }

        /** An archive next to the manifest. */
        fun fileUrl(base: String, file: String): String =
            manifestUrl(base).substringBeforeLast('/') + "/" + file
    }
}
