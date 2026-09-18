package app.tsumugi.speech

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

/** F-30: one file per synthesis, deleted after playback, leftovers cleaned. */
class SynthesizedAudioFilesTest {
    private val fs = FakeFileSystem()
    private val dir = "/data/tts".toPath()
    private val files = SynthesizedAudioFiles(fs, dir)

    @Test
    fun eachSynthesisGetsItsOwnFile() {
        val first = files.write(byteArrayOf(1))
        val second = files.write(byteArrayOf(2))
        assertNotEquals(first, second)
        // The first sentence is still playing: its audio must be intact.
        assertContentEquals(byteArrayOf(1), fs.read(first) { readByteArray() })
        files.delete(first)
        assertFalse(fs.exists(first))
        assertTrue(fs.exists(second))
    }

    @Test
    fun leftoversArePruned() {
        fs.createDirectories(dir)
        fs.write(dir / "voicevox.wav") { writeUtf8("v1 fixed name") }
        val old = files.write(byteArrayOf(1))
        val later = SynthesizedAudioFiles(fs, dir, nowMs = { Clock.System.now().toEpochMilliseconds() + 11.minutes.inWholeMilliseconds })
        val fresh = later.write(byteArrayOf(2))
        assertFalse(fs.exists(old), "older than 10 minutes")
        assertFalse(fs.exists(dir / "voicevox.wav"))
        assertTrue(fs.exists(fresh))
        files.prune()
        assertEquals(emptyList(), fs.list(dir))
    }

    @Test
    fun deleteIgnoresOtherPaths() {
        fs.createDirectories("/data".toPath())
        fs.write("/data/tsumugi.db".toPath()) { writeUtf8("user data") }
        files.delete("/data/tsumugi.db".toPath())
        assertTrue(fs.exists("/data/tsumugi.db".toPath()))
    }
}
