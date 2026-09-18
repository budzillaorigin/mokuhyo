package app.tsumugi.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

@Serializable
data class ModelManifest(val version: Int, val note: String = "", val models: List<ModelInfo>)

@Serializable
enum class ModelKind { LLM, STT }

@Serializable
data class ModelFile(val name: String, val url: String, val sha256: String, val bytes: Long)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val recommended: Boolean = false,
    val minRamGb: Int,
    val contextSize: Int = 0,
    val license: String,
    val files: List<ModelFile>,
) {
    val totalBytes: Long get() = files.sumOf { it.bytes }
}

sealed interface DownloadProgress {
    /** [bytesDone]/[bytesTotal] cover all of the model's files, including parts resumed from an earlier run. */
    data class Downloading(val file: String, val bytesDone: Long, val bytesTotal: Long) : DownloadProgress {
        val fraction: Double get() = if (bytesTotal <= 0) 0.0 else bytesDone.toDouble() / bytesTotal
    }
    data class Verifying(val file: String) : DownloadProgress
    data class Done(val path: Path) : DownloadProgress
    data class Failed(val message: String, val retryable: Boolean) : DownloadProgress
}

/**
 * Downloads, verifies and deletes on-device models listed in `content/models/manifest.json` (BRIEF §7.1).
 * Downloads resume with HTTP Range from a `.part` file; each file is SHA-256 verified before it is renamed into
 * place, and a `.sha256` marker records the verified hash so installed checks don't re-hash gigabytes.
 */
class ModelManager(
    private val fs: FileSystem,
    private val modelsDir: Path,
    engine: HttpClientEngine,
    val manifest: ModelManifest,
) {
    private val http = HttpClient(engine) { expectSuccess = false }

    fun models(kind: ModelKind): List<ModelInfo> = manifest.models.filter { it.kind == kind }

    fun model(id: String): ModelInfo? = manifest.models.firstOrNull { it.id == id }

    /**
     * The default pick for a device: the manifest's recommended model if the device has enough RAM for it,
     * otherwise the largest model that fits. Null if nothing fits (the UI then offers "use my own server").
     */
    fun recommend(kind: ModelKind, deviceRamGb: Double): ModelInfo? {
        val fitting = models(kind).filter { it.minRamGb <= deviceRamGb }
        return fitting.firstOrNull { it.recommended } ?: fitting.maxByOrNull { it.minRamGb }
    }

    fun isInstalled(model: ModelInfo): Boolean = model.files.all { isFileInstalled(dir(model), it) }

    /** Path to hand to the native loader (the first file; split GGUFs load the rest from the same folder). */
    fun modelPath(model: ModelInfo): Path? = if (isInstalled(model)) dir(model) / model.files.first().name else null

    fun installed(): List<ModelInfo> = manifest.models.filter(::isInstalled)

    /** Bytes on disk for [model], including partial downloads. */
    fun bytesOnDisk(model: ModelInfo): Long = model.files.sumOf { f ->
        (size(dir(model) / f.name) ?: 0L) + (size(dir(model) / "${f.name}.part") ?: 0L)
    }

    fun delete(model: ModelInfo) {
        fs.deleteRecursively(dir(model))
    }

    /**
     * Downloads every missing file of [model]. Cancelling the collector pauses the download; calling this again
     * resumes from the `.part` file. Emits [DownloadProgress.Done] or [DownloadProgress.Failed] last.
     */
    // channelFlow, not flow: progress is sent from inside Ktor's execute {} block, which may run in another
    // coroutine context (it does on Kotlin/Native), and flow {} forbids emitting across contexts.
    fun download(model: ModelInfo, progressStepBytes: Long = 1L shl 20): Flow<DownloadProgress> = channelFlow {
        val dir = dir(model)
        fs.createDirectories(dir)
        val total = model.totalBytes
        var doneBefore = 0L
        for (file in model.files) {
            if (isFileInstalled(dir, file)) {
                doneBefore += file.bytes
                continue
            }
            val part = dir / "${file.name}.part"
            var have = size(part) ?: 0L
            if (have > file.bytes) {
                fs.delete(part)
                have = 0
            }
            if (have < file.bytes) {
                val error = try {
                    http.prepareGet(file.url) { if (have > 0) header(HttpHeaders.Range, "bytes=$have-") }.execute { response ->
                        when {
                            response.status == HttpStatusCode.PartialContent -> Unit
                            response.status.isSuccess() -> have = 0 // server ignored Range: start over
                            else -> return@execute "download failed: HTTP ${response.status.value}"
                        }
                        val channel = response.bodyAsChannel()
                        val buffer = ByteArray(64 * 1024)
                        var lastEmit = have
                        val sink = if (have == 0L) fs.sink(part) else fs.appendingSink(part)
                        sink.buffer().use { out ->
                            while (true) {
                                val n = channel.readAvailable(buffer, 0, buffer.size)
                                if (n < 0) break
                                if (n == 0) continue
                                out.write(buffer, 0, n)
                                have += n
                                if (have - lastEmit >= progressStepBytes) {
                                    out.flush()
                                    lastEmit = have
                                    send(DownloadProgress.Downloading(file.name, doneBefore + have, total))
                                }
                            }
                        }
                        null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "download interrupted: ${e.message}"
                }
                if (error != null) {
                    send(DownloadProgress.Failed(error, retryable = true))
                    return@channelFlow
                }
                send(DownloadProgress.Downloading(file.name, doneBefore + have, total))
            }
            if (have != file.bytes) {
                send(DownloadProgress.Failed("${file.name}: expected ${file.bytes} bytes, got $have", retryable = true))
                return@channelFlow
            }
            send(DownloadProgress.Verifying(file.name))
            val actual = hashOf(part)
            if (!actual.equals(file.sha256, ignoreCase = true)) {
                fs.delete(part)
                send(DownloadProgress.Failed("${file.name}: checksum mismatch, the download was discarded", retryable = true))
                return@channelFlow
            }
            fs.atomicMove(part, dir / file.name)
            fs.write(dir / "${file.name}.sha256") { writeUtf8(actual) }
            doneBefore += file.bytes
        }
        send(DownloadProgress.Done(dir / model.files.first().name))
    }

    private fun dir(model: ModelInfo): Path = modelsDir / model.id

    private fun isFileInstalled(dir: Path, file: ModelFile): Boolean {
        val path = dir / file.name
        val marker = dir / "${file.name}.sha256"
        if (size(path) != file.bytes || !fs.exists(marker)) return false
        return fs.read(marker) { readUtf8().trim() }.equals(file.sha256, ignoreCase = true)
    }

    private fun size(path: Path): Long? = fs.metadataOrNull(path)?.size

    private fun hashOf(path: Path): String {
        val sha = Sha256()
        val buffer = ByteArray(256 * 1024)
        fs.source(path).buffer().use { src ->
            while (true) {
                val n = src.read(buffer, 0, buffer.size)
                if (n < 0) break
                sha.update(buffer, 0, n)
            }
        }
        return sha.hexDigest()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseManifest(text: String): ModelManifest = json.decodeFromString(ModelManifest.serializer(), text)
    }
}
