package app.tsumugi.content

import app.tsumugi.ai.Sha256
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-16: bundled packs open in place where possible; copies are verified, atomic, space-checked and tidy. */
class PackInstallerTest {
    private val fs = FakeFileSystem()
    private val packs = "/data/packs".toPath()
    private val file = PackInstaller.DICTIONARY
    private val content = ByteArray(200_000) { (it % 253).toByte() }

    private fun manifest(bytes: ByteArray = content, version: String = "v1", sha: String = Sha256.hex(bytes)) =
        """{"packs":[{"file":"$file","version":"$version","sha256":"$sha","bytes":${bytes.size}}]}"""

    /** A build whose bundle holds [bundled] under [manifestJson]; [inPlace] = the platform can open it as a file. */
    private fun installer(
        bundled: ByteArray = content,
        manifestJson: String = manifest(),
        inPlace: Path? = null,
        free: Long? = null,
    ) = PackInstaller(
        fs = fs,
        packsDir = packs,
        openBundled = { name ->
            when (name) {
                PackInstaller.MANIFEST -> Buffer().writeUtf8(manifestJson)
                file -> Buffer().write(bundled)
                else -> null
            }
        },
        bundledPath = { name -> inPlace?.takeIf { name == file } },
        freeBytes = { free },
    )

    private fun parts() = fs.listOrNull(packs).orEmpty().filter { it.name.endsWith(".part") }

    @Test
    fun copiesVerifiesAndRecordsVersion() = runTest {
        val installer = installer()
        assertEquals(PackStatus.Installed("v1"), installer.ensureInstalled(file))
        assertContentEquals(content, fs.read(packs / file) { readByteArray() })
        assertEquals("v1", installer.installedVersion(file))
        assertTrue(parts().isEmpty())
        assertNull(installer.progress.value)
        // Same version again: nothing to do.
        assertEquals(PackStatus.Installed("v1"), installer.ensureInstalled(file))
    }

    @Test
    fun hashMismatchIsRejectedAndLeavesNothingBehind() = runTest {
        val damaged = content.copyOf().also { it[123] = (it[123] + 1).toByte() }
        val installer = installer(bundled = damaged, manifestJson = manifest(sha = Sha256.hex(content)))
        val e = assertFailsWith<PackInstallException> { installer.ensureInstalled(file) }
        assertTrue("checksum" in e.message.orEmpty())
        assertFalse(fs.exists(packs / file))
        assertNull(installer.installedVersion(file))
        assertTrue(parts().isEmpty(), "no .part left: ${parts()}")
    }

    @Test
    fun failedUpgradeKeepsTheWorkingCopy() = runTest {
        installer().ensureInstalled(file)
        val newer = content.copyOf().also { it[0] = 42 }
        val broken = installer(bundled = newer, manifestJson = manifest(bytes = newer, version = "v2", sha = Sha256.hex(content)))
        assertEquals(PackStatus.Installed("v1"), broken.ensureInstalled(file))
        assertContentEquals(content, fs.read(packs / file) { readByteArray() })
    }

    @Test
    fun cleansStalePartFiles() = runTest {
        fs.createDirectories(packs)
        val stale = packs / "$file.0f8fad5b-d9cb-469f-a165-70867728950e.part"
        fs.write(stale) { writeUtf8("half a pack from a killed launch") }
        installer().ensureInstalled(file)
        assertFalse(fs.exists(stale))
        assertTrue(parts().isEmpty())
    }

    @Test
    fun checksFreeSpaceBeforeCopying() = runTest {
        val e = assertFailsWith<PackInstallException> { installer(free = content.size.toLong()).ensureInstalled(file) }
        assertTrue("Not enough storage" in e.message.orEmpty(), e.message)
        assertFalse(fs.exists(packs / file))
        assertEquals(PackStatus.Installed("v1"), installer(free = content.size * 2L).ensureInstalled(file))
    }

    @Test
    fun wrongSizeIsRejected() = runTest {
        val short = content.copyOf(1000)
        val installer = installer(bundled = short, manifestJson = manifest(sha = Sha256.hex(short)))
        assertFailsWith<PackInstallException> { installer.ensureInstalled(file) }
        assertFalse(fs.exists(packs / file))
    }

    @Test
    fun opensInPlaceWithoutCopyingAndDropsOldCopies() = runTest {
        installer().ensureInstalled(file) // a v1 build copied it
        val inBundle = "/app/Tsumugi.app/packs/$file".toPath()
        val status = installer(inPlace = inBundle).ensureInstalled(file)
        assertEquals(PackStatus.Installed("v1"), status)
        assertFalse(fs.exists(packs / file), "the copy is gone")
        assertFalse(fs.exists(packs / "$file.version"))
    }

    @Test
    fun missingWhenNeitherBundledNorInstalled() = runTest {
        val installer = PackInstaller(fs, packs, openBundled = { null }, bundledPath = { null })
        assertEquals(PackStatus.Missing, installer.ensureInstalled(file))
    }

    @Test
    fun downloadedPacksAreVerifiedToo() = runTest {
        val installer = PackInstaller(fs, packs, openBundled = { null }, bundledPath = { null })
        val pack = PackFile(PackInstaller.EXAM, "e1", Sha256.hex(content), content.size.toLong())
        installer.installFrom(pack, Buffer().write(content))
        assertEquals("e1", installer.installedVersion(PackInstaller.EXAM))
        assertFailsWith<PackInstallException> {
            installer.installFrom(pack.copy(version = "e2"), Buffer().write(content.copyOf(10)))
        }
        assertEquals("e1", installer.installedVersion(PackInstaller.EXAM))
    }
}
