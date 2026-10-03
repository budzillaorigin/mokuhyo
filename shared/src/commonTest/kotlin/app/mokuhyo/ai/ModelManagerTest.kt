package app.mokuhyo.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.Source
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelManagerTest {
    private val partA = ByteArray(300_000) { (it % 251).toByte() }
    private val partB = ByteArray(1_000) { (it % 7).toByte() }
    private val split = ModelInfo(
        id = "split-llm", name = "Split", kind = ModelKind.LLM, minRamGb = 12, license = "Apache-2.0",
        files = listOf(
            ModelFile("m-00001-of-00002.gguf", "https://hf.test/a", Sha256.hex(partA), partA.size.toLong()),
            ModelFile("m-00002-of-00002.gguf", "https://hf.test/b", Sha256.hex(partB), partB.size.toLong()),
        ),
    )
    private val small = split.copy(id = "small-llm", minRamGb = 4, recommended = true, files = split.files.take(1))
    private val stt = ModelInfo("stt", "STT", ModelKind.STT, minRamGb = 2, license = "MIT", files = split.files.drop(1))
    private val manifest = ModelManifest(1, models = listOf(small, split, stt))

    private val fs = FakeFileSystem()
    private val dir = "/models".toPath()
    private val ranges = mutableListOf<String?>()

    /** Serves both files, honouring `Range: bytes=N-` when [honourRange]. */
    private fun engine(honourRange: Boolean = true, corrupt: Boolean = false) = MockEngine { req ->
        val data = when (req.url.toString()) {
            "https://hf.test/a" -> partA
            "https://hf.test/b" -> partB
            else -> error("unexpected ${req.url}")
        }.let { if (corrupt) it.copyOf().also { c -> c[0] = (c[0] + 1).toByte() } else it }
        val range = req.headers[HttpHeaders.Range]
        ranges += range
        if (range != null && honourRange) {
            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            respond(data.copyOfRange(from, data.size), HttpStatusCode.PartialContent)
        } else {
            respond(data, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, data.size.toString()))
        }
    }

    @Test
    fun downloadsVerifiesAndInstallsAllParts() = runTest {
        val mm = ModelManager(fs, dir, engine(), manifest)
        assertFalse(mm.isInstalled(split))
        val events = mm.download(split, progressStepBytes = 64 * 1024).toList()
        val done = events.last()
        assertIs<DownloadProgress.Done>(done)
        assertEquals(dir / "split-llm" / "m-00001-of-00002.gguf", done.path)
        assertTrue(mm.isInstalled(split))
        assertEquals(done.path, mm.modelPath(split))
        assertContentEquals(partA, fs.read(done.path) { readByteArray() })
        val progress = events.filterIsInstance<DownloadProgress.Downloading>()
        assertEquals(progress.map { it.bytesDone }.sorted(), progress.map { it.bytesDone }, "progress is monotonic")
        assertEquals(split.totalBytes, progress.last().bytesDone)
        assertEquals(2, events.count { it is DownloadProgress.Verifying })
        assertEquals(listOf(split), mm.installed())
    }

    @Test
    fun resumesFromPartialFile() = runTest {
        fs.createDirectories(dir / "split-llm")
        fs.write(dir / "split-llm" / "m-00001-of-00002.gguf.part") { write(partA, 0, 100_000) }
        val mm = ModelManager(fs, dir, engine(), manifest)
        assertEquals(100_000, mm.bytesOnDisk(split))
        val done = mm.download(split).toList().last()
        assertIs<DownloadProgress.Done>(done)
        assertEquals(listOf("bytes=100000-", null), ranges)
        assertContentEquals(partA, fs.read(done.path) { readByteArray() })
    }

    @Test
    fun restartsWhenServerIgnoresRange() = runTest {
        fs.createDirectories(dir / "small-llm")
        fs.write(dir / "small-llm" / "m-00001-of-00002.gguf.part") { write(partA, 0, 5_000) }
        val mm = ModelManager(fs, dir, engine(honourRange = false), manifest)
        assertIs<DownloadProgress.Done>(mm.download(small).toList().last())
        assertTrue(mm.isInstalled(small))
    }

    @Test
    fun checksumMismatchDiscardsDownload() = runTest {
        val mm = ModelManager(fs, dir, engine(corrupt = true), manifest)
        val last = mm.download(small).toList().last()
        assertIs<DownloadProgress.Failed>(last)
        assertTrue("checksum" in last.message)
        assertFalse(fs.exists(dir / "small-llm" / "m-00001-of-00002.gguf.part"))
        assertFalse(mm.isInstalled(small))
        assertNull(mm.modelPath(small))
    }

    @Test
    fun httpErrorIsRetryableFailure() = runTest {
        val mm = ModelManager(fs, dir, MockEngine { respond("", HttpStatusCode.NotFound) }, manifest)
        val last = mm.download(small).toList().last()
        assertIs<DownloadProgress.Failed>(last)
        assertTrue(last.retryable)
    }

    @Test
    fun skipsInstalledFilesAndDeletes() = runTest {
        val mm = ModelManager(fs, dir, engine(), manifest)
        mm.download(small).toList()
        ranges.clear()
        // split-llm shares nothing on disk with small-llm (separate folders), so both files download.
        mm.download(split).toList()
        assertEquals(2, ranges.size)
        ranges.clear()
        assertIs<DownloadProgress.Done>(mm.download(split).toList().single())
        assertTrue(ranges.isEmpty())
        mm.delete(split)
        assertFalse(mm.isInstalled(split))
        assertTrue(mm.isInstalled(small))
    }

    // --- F-13 ---

    /** Counts reads of `.part` files: the hash must come from the bytes as they're written, not a second pass. */
    private class CountingFs(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        var partReads = 0
        override fun source(file: Path): Source {
            if (file.name.endsWith(".part")) partReads++
            return super.source(file)
        }
    }

    @Test
    fun hashesWhileWritingWithoutSecondPass() = runTest {
        val counting = CountingFs(fs)
        val mm = ModelManager(counting, dir, engine(), manifest)
        assertIs<DownloadProgress.Done>(mm.download(split).toList().last())
        assertEquals(0, counting.partReads)
        assertTrue(mm.isInstalled(split))
    }

    @Test
    fun resumeRehashesThePartialFileOnce() = runTest {
        val counting = CountingFs(fs)
        fs.createDirectories(dir / "small-llm")
        fs.write(dir / "small-llm" / "m-00001-of-00002.gguf.part") { write(partA, 0, 123_456) }
        val mm = ModelManager(counting, dir, engine(), manifest)
        assertIs<DownloadProgress.Done>(mm.download(small).toList().last())
        assertEquals(1, counting.partReads)
        assertEquals(listOf<String?>("bytes=123456-"), ranges)
        assertTrue(mm.isInstalled(small))
    }

    @Test
    fun corruptResumedPartIsCaughtByIncrementalHash() = runTest {
        fs.createDirectories(dir / "small-llm")
        val bad = partA.copyOf(50_000).also { it[10] = (it[10] + 1).toByte() }
        fs.write(dir / "small-llm" / "m-00001-of-00002.gguf.part") { write(bad) }
        val mm = ModelManager(fs, dir, engine(), manifest)
        val last = mm.download(small).toList().last()
        assertIs<DownloadProgress.Failed>(last)
        assertTrue("checksum" in last.message)
    }

    @Test
    fun refusesToStartWithoutOneAndAHalfTimesTheSpace() = runTest {
        var asked: Path? = null
        val tight = ModelManager(fs, dir, engine(), manifest, freeBytes = { asked = it; partA.size * 3L / 2 - 1 })
        val last = tight.download(small).toList().single()
        assertIs<DownloadProgress.Failed>(last)
        assertFalse(last.retryable)
        assertTrue("Not enough storage" in last.message, last.message)
        assertEquals(dir / "small-llm", asked)
        assertTrue(ranges.isEmpty(), "no bytes requested")

        val enough = ModelManager(fs, dir, engine(), manifest, freeBytes = { partA.size * 3L / 2 })
        assertIs<DownloadProgress.Done>(enough.download(small).toList().last())
    }

    @Test
    fun recommendsByRam() {
        val mm = ModelManager(fs, dir, engine(), manifest)
        assertEquals(small, mm.recommend(ModelKind.LLM, 6.0))
        assertEquals(small, mm.recommend(ModelKind.LLM, 16.0))
        assertNull(mm.recommend(ModelKind.LLM, 3.0))
        assertEquals(stt, mm.recommend(ModelKind.STT, 3.0))
    }

    @Test
    fun parsesManifest() {
        val m = ModelManager.parseManifest(
            """{"version":1,"note":"n","future":true,"models":[{"id":"whisper-base","name":"Whisper base","kind":"STT","recommended":true,""" +
                """"minRamGb":2,"license":"MIT","files":[{"name":"ggml-base.bin","url":"https://u","sha256":"ab","bytes":147951465}]}]}""",
        )
        assertEquals(147951465L, m.models.single().totalBytes)
        assertEquals(ModelKind.STT, m.models.single().kind)
    }

    @Test
    fun sideLoadsAVerifiedFileWithoutNetwork() {
        val mm = ModelManager(fs, dir, MockEngine { error("no network for a side-load") }, manifest)
        val usb = "/usb/model.gguf".toPath()
        fs.createDirectories("/usb".toPath())
        fs.write(usb) { write(partA) }
        assertTrue(mm.installFromFile(small, usb).isSuccess)
        assertTrue(mm.isInstalled(small))
        val bad = "/usb/bad.gguf".toPath()
        fs.write(bad) { write(partA.copyOf().also { it[5] = (it[5] + 1).toByte() }) }
        val r = mm.installFromFile(small.copy(id = "other-llm"), bad)
        assertTrue(r.isFailure && "SHA-256" in r.exceptionOrNull()!!.message!!)
        assertTrue(mm.installFromFile(split, usb).isFailure, "multi-file models are not side-loaded")
    }
}
