package app.tsumugi.api

import app.tsumugi.ai.ModelFile
import app.tsumugi.ai.ModelInfo
import app.tsumugi.ai.ModelKind
import app.tsumugi.ai.ModelManager
import app.tsumugi.ai.ModelManifest
import app.tsumugi.ai.Sha256
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iOS background-download hand-off (F-13): files verified here count as installed for [ModelManager]. */
class DownloadedModelInstallerTest {
    private val partA = ByteArray(200_000) { (it % 251).toByte() }
    private val partB = ByteArray(1_000) { (it % 7).toByte() }
    private val model = ModelInfo(
        id = "split-llm", name = "Split", kind = ModelKind.LLM, minRamGb = 4, license = "Apache-2.0",
        files = listOf(
            ModelFile("m-1.gguf", "https://hf.test/a", Sha256.hex(partA), partA.size.toLong()),
            ModelFile("m-2.gguf", "https://hf.test/b", Sha256.hex(partB), partB.size.toLong()),
        ),
    )
    private val fs = FakeFileSystem()
    private val dir = "/models".toPath()
    private val manager = ModelManager(fs, dir, MockEngine { error("no network in this test") }, ModelManifest(1, models = listOf(model)))

    @Test
    fun verifiedFilesAreInstalledForModelManager() = runTest {
        val installer = DownloadedModelInstaller(fs, dir)
        val plan = installer.plan(model)
        assertNull(plan.error)
        assertEquals(listOf("m-1.gguf", "m-2.gguf"), plan.files.map { it.name })
        assertFalse(plan.files.any { it.downloaded })

        fs.write(plan.files[0].targetPath.toPath()) { write(partA) }
        assertTrue(installer.plan(model).files.first().downloaded, "a finished but unverified file is reported")
        assertNull(installer.install(model, "m-1.gguf", plan.files[0].targetPath))
        assertFalse(fs.exists(plan.files[0].targetPath.toPath()), "the download is moved, not copied")
        assertEquals(listOf("m-2.gguf"), installer.plan(model).files.map { it.name })
        assertFalse(manager.isInstalled(model))

        fs.write(plan.files[1].targetPath.toPath()) { write(partB) }
        assertNull(installer.install(model, "m-2.gguf", plan.files[1].targetPath))
        assertTrue(manager.isInstalled(model))
        assertContentEquals(partA, fs.read(dir / "split-llm" / "m-1.gguf") { readByteArray() })
        assertTrue(installer.plan(model).files.isEmpty())
    }

    @Test
    fun corruptOrShortFilesAreRejectedAndDeleted() = runTest {
        val installer = DownloadedModelInstaller(fs, dir)
        val target = installer.plan(model).files[0].targetPath.toPath()
        fs.write(target) { write(partA.copyOf().also { it[0] = (it[0] + 1).toByte() }) }
        assertNotNull(installer.install(model, "m-1.gguf", target.toString()))
        assertFalse(fs.exists(target))
        fs.write(target) { write(partA, 0, 10) }
        assertNotNull(installer.install(model, "m-1.gguf", target.toString()))
        assertFalse(fs.exists(target))
        assertFalse(manager.isInstalled(model))
    }

    @Test
    fun notEnoughStorageIsNotRetryable() {
        val installer = DownloadedModelInstaller(fs, dir, freeBytes = { 1_000L })
        val plan = installer.plan(model)
        assertTrue(plan.files.isEmpty())
        assertFalse(plan.retryable)
        assertTrue(plan.error!!.startsWith("Not enough storage"))
    }

    @Test
    fun staleInProcessPartFilesAreDropped() {
        fs.createDirectories(dir / "split-llm")
        fs.write(dir / "split-llm" / "m-1.gguf.part") { write(partA, 0, 100) }
        DownloadedModelInstaller(fs, dir).plan(model)
        assertFalse(fs.exists(dir / "split-llm" / "m-1.gguf.part"))
    }
}
