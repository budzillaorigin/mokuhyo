package app.tsumugi.audio

import app.tsumugi.ai.Sha256
import app.tsumugi.content.PackInstallException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D-095: audio packs install atomically, verify what they can, and resolve clip keys to plain files. */
@OptIn(ExperimentalEncodingApi::class)
class AudioPackRepositoryTest {
    private val fs = FakeFileSystem()
    private val audioDir = "/data/audio".toPath()

    private fun repo(
        bundled: Map<String, ByteArray> = emptyMap(),
        http: MockEngine? = null,
        free: Long? = null,
    ) = AudioPackRepository(
        fs = fs,
        audioDir = audioDir,
        openBundled = { name -> bundled[name]?.let { Buffer().write(it) } },
        httpEngine = http?.let { engine -> { engine } },
        freeBytes = { free },
    )

    private fun entry(zip: ByteArray, set: String = "pitch", sha: String = Sha256.hex(zip)) =
        AudioPackEntry(file = "audio-$set.zip", set = set, version = "1-${sha.take(12)}", sha256 = sha, bytes = zip.size.toLong())

    private fun leftovers() = fs.listRecursively(audioDir).filter { it.name.endsWith(".part") || it.name.endsWith(".old") }.toList()

    @Test
    fun installsThePythonBuiltFixtureAndResolvesKeys() = runTest {
        val repo = repo()
        val zip = FIXTURE
        val installed = repo.installFrom(Buffer().write(zip), entry(zip))

        assertEquals(AudioSet.PITCH, installed.set)
        assertEquals(3, installed.clips)
        assertEquals(listOf("VOICEVOX:春日部つむぎ"), installed.credits)
        val p1 = assertNotNull(repo.clip(AudioKeys.pitch("p1")))
        assertContentEquals("fake-m4a-clip-A".encodeToByteArray(), fs.read(p1) { readByteArray() })
        assertContentEquals("fake-m4a-clip-B!".encodeToByteArray(), fs.read(repo.clip("pitch/p2")!!) { readByteArray() })
        // Two keys sharing one archive entry each get their own file.
        assertNotNull(repo.clip("pitch/p3"))
        assertNull(repo.clip("pitch/p4"))
        assertNull(repo.clip(AudioKeys.exam("jla-n1-qr-01", 0)), "set not installed: fall back to TTS")
        assertEquals(listOf(installed), repo.installed())
        assertEquals(listOf("VOICEVOX:春日部つむぎ"), repo.credits())
        assertTrue(leftovers().isEmpty())
        assertTrue(fs.listOrNull(audioDir / "incoming").orEmpty().isEmpty(), "the archive is removed after extraction")
    }

    @Test
    fun pitchItemsListOnlyItemsWithAudio() = runTest {
        val repo = repo()
        assertTrue(repo.pitchItems().isEmpty(), "no pitch pack: the test stays hidden")
        repo.installFrom(Buffer().write(FIXTURE))
        val items = repo.pitchItems()
        assertEquals(listOf("p1", "p2"), items.map { it.id }) // p9 has no clip in the fixture
        assertEquals(1, items[0].downstep)
        assertEquals(listOf("p2"), items[0].confusableWith)
        assertEquals("pitch", repo.index(AudioSet.PITCH)!!.set)
    }

    @Test
    fun pickedFileWithoutManifestIsVersionedByItsHash() = runTest {
        val repo = repo()
        val installed = repo.installFrom(Buffer().write(FIXTURE))
        assertEquals("sha256:${Sha256.hex(FIXTURE).take(12)}", installed.version)
        fs.createDirectories("/inbox".toPath())
        fs.write("/inbox/audio-pitch.zip".toPath()) { write(FIXTURE) }
        assertEquals(installed.version, repo.installFile("/inbox/audio-pitch.zip".toPath()).version)
    }

    @Test
    fun checksumMismatchKeepsThePreviousVersion() = runTest {
        val repo = repo()
        val v1 = repo.installFrom(Buffer().write(FIXTURE), entry(FIXTURE))
        val v2 = TestZip.pack("pitch", mapOf("pitch/p1" to "new".encodeToByteArray()))
        assertFailsWith<PackInstallException> {
            repo.installFrom(Buffer().write(v2), entry(v2, sha = "0".repeat(64)))
        }
        assertEquals(v1, repo.installedPack(AudioSet.PITCH))
        assertContentEquals("fake-m4a-clip-A".encodeToByteArray(), fs.read(repo.clip("pitch/p1")!!) { readByteArray() })
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun updateReplacesTheWholeSet() = runTest {
        val repo = repo()
        repo.installFrom(Buffer().write(FIXTURE), entry(FIXTURE))
        val v2 = TestZip.pack("pitch", mapOf("pitch/p1" to "new".encodeToByteArray()))
        val installed = repo.installFrom(Buffer().write(v2), entry(v2))
        assertEquals(1, installed.clips)
        assertContentEquals("new".encodeToByteArray(), fs.read(repo.clip("pitch/p1")!!) { readByteArray() })
        assertNull(repo.clip("pitch/p2"), "clips dropped from the new version are gone")
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun damagedOrForeignArchivesAreRejected() = runTest {
        val repo = repo()
        val wrongSize = TestZip.pack("pitch", mapOf("pitch/p1" to "abc".encodeToByteArray()), sizeDelta = 1)
        assertFailsWith<PackInstallException> { repo.installFrom(Buffer().write(wrongSize)) }
        val foreignKey = TestZip.pack("pitch", mapOf("exam/x/0" to "abc".encodeToByteArray()))
        assertFailsWith<PackInstallException> { repo.installFrom(Buffer().write(foreignKey)) }
        val newer = TestZip.pack("pitch", mapOf("pitch/p1" to "abc".encodeToByteArray()), format = 2)
        assertFailsWith<PackInstallException> { repo.installFrom(Buffer().write(newer)) }
        val unknownSet = TestZip.pack("karaoke", mapOf("karaoke/1" to "abc".encodeToByteArray()))
        assertFailsWith<PackInstallException> { repo.installFrom(Buffer().write(unknownSet)) }
        assertFailsWith<PackInstallException> { repo.installFrom(Buffer().writeUtf8("not a zip at all")) }
        val exam = TestZip.pack("exam", mapOf("exam/x/0" to "abc".encodeToByteArray()))
        assertFailsWith<PackInstallException>("manifest entry says pitch, archive is exam") {
            repo.installFrom(Buffer().write(exam), entry(exam, set = "pitch"))
        }
        assertTrue(repo.installed().isEmpty())
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun trackDialoguesResolveFromTheTracksPack() = runTest {
        // D-240: track dialogues keep dialogue/<id>/<ord> keys (the shared screens build them), but ship in
        // audio-tracks.zip next to the perform/<drill>/<line> clips.
        val repo = repo()
        val dialogues = TestZip.pack("dialogues", mapOf(AudioKeys.dialogue("n5-morning", 0) to "practice".encodeToByteArray()))
        val tracks = TestZip.pack("tracks", mapOf(
            AudioKeys.dialogue("business-dl-001", 0) to "track".encodeToByteArray(),
            AudioKeys.performance("performing-perf-001", 2) to "perform".encodeToByteArray(),
        ))
        assertNull(repo.clip(AudioKeys.dialogue("business-dl-001", 0)))
        assertEquals(AudioSet.TRACKS, repo.installFrom(Buffer().write(tracks)).set)
        assertContentEquals("track".encodeToByteArray(), fs.read(repo.clip(AudioKeys.dialogue("business-dl-001", 0))!!) { readByteArray() })
        assertContentEquals("perform".encodeToByteArray(), fs.read(repo.clip(AudioKeys.performance("performing-perf-001", 2))!!) { readByteArray() })
        assertNull(repo.clip(AudioKeys.dialogue("n5-morning", 0)), "dialogues pack not installed")
        repo.installFrom(Buffer().write(dialogues))
        assertContentEquals("practice".encodeToByteArray(), fs.read(repo.clip(AudioKeys.dialogue("n5-morning", 0))!!) { readByteArray() })
        assertNotNull(repo.clip(AudioKeys.dialogue("business-dl-001", 0)), "still found in the tracks pack")
        val foreign = TestZip.pack("tracks", mapOf(AudioKeys.reader("gr-n5-001", 0) to "x".encodeToByteArray()))
        assertFailsWith<PackInstallException> { repo.installFrom(Buffer().write(foreign)) }
        val performInDialogues = TestZip.pack("dialogues", mapOf(AudioKeys.performance("p", 0) to "x".encodeToByteArray()))
        assertFailsWith<PackInstallException> { repo.installFrom(Buffer().write(performInDialogues)) }
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun notEnoughSpaceFailsBeforeWriting() = runTest {
        val repo = repo(free = 10)
        assertFailsWith<PackInstallException> { repo.installFrom(Buffer().write(FIXTURE), entry(FIXTURE)) }
        assertFalse(repo.isInstalled(AudioSet.PITCH))
    }

    @Test
    fun downloadsVerifiesAndInstalls() = runTest {
        val zip = TestZip.pack("dialogues", mapOf(AudioKeys.dialogue("n5-morning", 0) to "hello".encodeToByteArray()))
        val good = entry(zip, set = "dialogues")
        val manifest = """{"format":1,"packs":[{"file":"audio-dialogues.zip","set":"dialogues","version":"${good.version}",
            "sha256":"${good.sha256}","bytes":${zip.size},"clips":1,"credits":["VOICEVOX:玄野武宏"]}]}"""
        val requested = mutableListOf<String>()
        val http = MockEngine { request ->
            requested += request.url.toString()
            when (request.url.encodedPath) {
                "/tsumugi/audio/audio-manifest.json" -> respond(manifest, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                "/tsumugi/audio/audio-dialogues.zip" -> respond(zip, HttpStatusCode.OK)
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val repo = repo(http = http)
        val listed = repo.fetchManifest("https://home.example/tsumugi/audio/")
        assertEquals(good, listed.packs.single().copy(clips = 0, audioSeconds = 0, credits = emptyList()))
        val installed = repo.download("https://home.example/tsumugi/audio", listed.packs.single())
        assertEquals(good.version, installed.version)
        assertNotNull(repo.clip("dialogue/n5-morning/0"))
        assertEquals("https://home.example/tsumugi/audio/audio-dialogues.zip", requested.last())

        // A tampered download is discarded and the installed set stays.
        val tampered = listed.packs.single().copy(sha256 = "f".repeat(64), version = "1-ffffffffffff")
        assertFailsWith<PackInstallException> { repo.download("https://home.example/tsumugi/audio", tampered) }
        assertEquals(good.version, repo.installedPack(AudioSet.DIALOGUES)!!.version)
        assertTrue(leftovers().isEmpty())
    }

    @Test
    fun bundledSetsInstallOncePerVersion() = runTest {
        val manifest = """{"packs":[{"file":"audio-pitch.zip","set":"pitch","version":"1-abc","sha256":"${Sha256.hex(FIXTURE)}",
            "bytes":${FIXTURE.size}},{"file":"audio-exam.zip","set":"exam","version":"1-def","sha256":"00","bytes":1}]}"""
        val repo = repo(bundled = mapOf(AudioPackRepository.MANIFEST to manifest.encodeToByteArray(), "audio-pitch.zip" to FIXTURE))
        assertEquals(listOf(AudioSet.PITCH), repo.ensureBundled().map { it.set }, "exam isn't bundled: skipped")
        assertTrue(repo.ensureBundled().isEmpty(), "same version: nothing to do")
        assertEquals("1-abc", repo.installedPack(AudioSet.PITCH)!!.version)
        assertTrue(repo(bundled = emptyMap()).ensureBundled().isEmpty())
    }

    @Test
    fun removeFallsBackToTts() = runTest {
        val repo = repo()
        repo.installFrom(Buffer().write(FIXTURE))
        repo.remove(AudioSet.PITCH)
        assertNull(repo.clip("pitch/p1"))
        assertTrue(repo.installed().isEmpty())
    }

    @Test
    fun manifestUrls() {
        assertEquals("https://h/a/audio-manifest.json", AudioPackRepository.manifestUrl("https://h/a/"))
        assertEquals("https://h/a/audio-manifest.json", AudioPackRepository.manifestUrl("https://h/a"))
        assertEquals("https://h/a/list.json", AudioPackRepository.manifestUrl(" https://h/a/list.json "))
        assertEquals("https://h/a/audio-exam.zip", AudioPackRepository.fileUrl("https://h/a/list.json", "audio-exam.zip"))
    }

    companion object {
        /** Built by `render_audio.write_zip` (the real packager): pitch set, keys p1..p3, items p1, p2, p9. */
        val FIXTURE: ByteArray = Base64.decode(
            """
            UEsDBBQAAAAAAAAAIQBzumW2DwAAAA8AAAAOAAAAY2xpcHMvYWFhYS5tNGFmYWtlLW00YS1jbGlwLUFQSwMEFAAAAAAAAAAhACzp
            /HwQAAAAEAAAAA4AAABjbGlwcy9iYmJiLm00YWZha2UtbTRhLWNsaXAtQiFQSwMEFAAAAAAAAAAhAJU7CPOUAgAAlAIAAAoAAABp
            bmRleC5qc29ueyJmb3JtYXQiOiAxLCAic2V0IjogInBpdGNoIiwgImNvZGVjIjogImFhYy1sYyIsICJjb250YWluZXIiOiAibTRh
            IiwgInNhbXBsZVJhdGUiOiAyNDAwMCwgImNoYW5uZWxzIjogMSwgImJpdHJhdGUiOiAiNDhrIiwgImVuZ2luZSI6ICJWT0lDRVZP
            WCBFbmdpbmUgMC4yNS4yIiwgImNyZWRpdHMiOiBbIlZPSUNFVk9YOuaYpeaXpemDqOOBpOOCgOOBjiJdLCAiY2xpcHMiOiB7InBp
            dGNoL3AxIjogeyJmaWxlIjogImNsaXBzL2FhYWEubTRhIiwgImJ5dGVzIjogMTUsICJtcyI6IDcwMCwgInZvaWNlIjogIuaYpeaX
            pemDqOOBpOOCgOOBjiIsICJ0ZXh0IjogIuOBr+OBl+OBjCIsICJkb3duc3RlcCI6IDEsICJkaXNwbGF5IjogIueuuCJ9LCAicGl0
            Y2gvcDIiOiB7ImZpbGUiOiAiY2xpcHMvYmJiYi5tNGEiLCAiYnl0ZXMiOiAxNiwgIm1zIjogNzEwLCAidm9pY2UiOiAi5pil5pel
            6YOo44Gk44KA44GOIiwgInRleHQiOiAi44Gv44GX44GMIiwgImRvd25zdGVwIjogMiwgImRpc3BsYXkiOiAi5qmLIn0sICJwaXRj
            aC9wMyI6IHsiZmlsZSI6ICJjbGlwcy9hYWFhLm00YSIsICJieXRlcyI6IDE1LCAibXMiOiA3MDAsICJ2b2ljZSI6ICLmmKXml6Xp
            g6jjgaTjgoDjgY4iLCAidGV4dCI6ICLjga/jgZfjgYwiLCAiZG93bnN0ZXAiOiAxLCAiZGlzcGxheSI6ICLnrrgifX19UEsDBBQA
            AAAAAAAAIQBr0t1riwIAAIsCAAAKAAAAaXRlbXMuanNvbnsiZm9ybWF0IjogMSwgImNhcnJpZXIiOiAi44GMIiwgIml0ZW1zIjog
            W3siaWQiOiAicDEiLCAiZW50cnlJZCI6IDEsICJ0ZXh0IjogIueuuCIsICJyZWFkaW5nIjogIuOBr+OBlyIsICJtb3JhQ291bnQi
            OiAyLCAiZG93bnN0ZXAiOiAxLCAicGF0dGVybiI6ICJhdGFtYWRha2EiLCAiZ3JvdXAiOiAi44Gv44GXIiwgImdsb3NzIjogImNo
            b3BzdGlja3MiLCAic3Bva2VuIjogIuOBr+OBl+OBjCIsICJjb25mdXNhYmxlV2l0aCI6IFsicDIiXX0sIHsiaWQiOiAicDIiLCAi
            ZW50cnlJZCI6IDIsICJ0ZXh0IjogIuapiyIsICJyZWFkaW5nIjogIuOBr+OBlyIsICJtb3JhQ291bnQiOiAyLCAiZG93bnN0ZXAi
            OiAyLCAicGF0dGVybiI6ICJvZGFrYSIsICJncm91cCI6ICLjga/jgZciLCAiZ2xvc3MiOiAiYnJpZGdlIiwgInNwb2tlbiI6ICLj
            ga/jgZfjgYwiLCAiY29uZnVzYWJsZVdpdGgiOiBbInAxIl19LCB7ImlkIjogInA5IiwgImVudHJ5SWQiOiA5LCAidGV4dCI6ICLn
            q68iLCAicmVhZGluZyI6ICLjga/jgZciLCAibW9yYUNvdW50IjogMiwgImRvd25zdGVwIjogMCwgInBhdHRlcm4iOiAiaGVpYmFu
            IiwgImdyb3VwIjogIuOBr+OBlyIsICJnbG9zcyI6ICJlZGdlIiwgInNwb2tlbiI6ICLjga/jgZfjgYwiLCAiY29uZnVzYWJsZVdp
            dGgiOiBbXX1dfVBLAQIUABQAAAAAAAAAIQBzumW2DwAAAA8AAAAOAAAAAAAAAAAAAACkAQAAAABjbGlwcy9hYWFhLm00YVBLAQIU
            ABQAAAAAAAAAIQAs6fx8EAAAABAAAAAOAAAAAAAAAAAAAACkATsAAABjbGlwcy9iYmJiLm00YVBLAQIUABQAAAAAAAAAIQCVOwjz
            lAIAAJQCAAAKAAAAAAAAAAAAAACkAXcAAABpbmRleC5qc29uUEsBAhQAFAAAAAAAAAAhAGvS3WuLAgAAiwIAAAoAAAAAAAAAAAAA
            AKQBMwMAAGl0ZW1zLmpzb25QSwUGAAAAAAQABADoAAAA5gUAAAAA
            """.lines().joinToString("") { it.trim() },
        )
    }
}

/** A minimal stored-entry zip writer for test archives (the production packager is tools/packs/render_audio.py). */
internal object TestZip {
    /** An audio pack of [set] with one entry per key; [sizeDelta] corrupts the sizes the index claims. */
    fun pack(set: String, clips: Map<String, ByteArray>, format: Int = 1, sizeDelta: Int = 0): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        val index = clips.entries.mapIndexed { i, (key, data) ->
            files["clips/$i.m4a"] = data
            "\"$key\":{\"file\":\"clips/$i.m4a\",\"bytes\":${data.size + sizeDelta}}"
        }.joinToString(",")
        files["index.json"] = """{"format":$format,"set":"$set","credits":["VOICEVOX:四国めたん"],"clips":{$index}}""".encodeToByteArray()
        return zip(files)
    }

    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = Buffer()
        val central = Buffer()
        var count = 0
        for ((name, data) in files) {
            val offset = out.size
            val nameBytes = name.encodeToByteArray()
            val crc = crc32(data)
            out.writeIntLe(0x04034b50).writeShortLe(20).writeShortLe(0).writeShortLe(0).writeShortLe(0).writeShortLe(0x21)
                .writeIntLe(crc).writeIntLe(data.size).writeIntLe(data.size).writeShortLe(nameBytes.size).writeShortLe(0)
                .write(nameBytes).write(data)
            central.writeIntLe(0x02014b50).writeShortLe(20).writeShortLe(20).writeShortLe(0).writeShortLe(0)
                .writeShortLe(0).writeShortLe(0x21).writeIntLe(crc).writeIntLe(data.size).writeIntLe(data.size)
                .writeShortLe(nameBytes.size).writeShortLe(0).writeShortLe(0).writeShortLe(0).writeShortLe(0)
                .writeIntLe(0).writeIntLe(offset.toInt()).write(nameBytes)
            count++
        }
        val centralOffset = out.size
        val centralSize = central.size
        out.writeAll(central)
        out.writeIntLe(0x06054b50).writeShortLe(0).writeShortLe(0).writeShortLe(count).writeShortLe(count)
            .writeIntLe(centralSize.toInt()).writeIntLe(centralOffset.toInt()).writeShortLe(0)
        return out.readByteArray()
    }

    private fun crc32(data: ByteArray): Int {
        var crc = -1
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
        }
        return crc.inv()
    }
}
