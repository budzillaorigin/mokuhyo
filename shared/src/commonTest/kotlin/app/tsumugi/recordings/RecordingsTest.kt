package app.tsumugi.recordings

import app.tsumugi.cards.PersonalCards
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.domain.ItemSource
import app.tsumugi.settings.DeviceSettings
import app.tsumugi.srs.NewItem
import app.tsumugi.srs.SrsRepository
import app.tsumugi.sync.BlobStore
import app.tsumugi.sync.E2eKeys
import app.tsumugi.sync.E2eSealer
import app.tsumugi.sync.Sealer
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
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

/** The server's blob store in memory, shared by every device of one account. */
class FakeBlobStore : BlobStore {
    val blobs = LinkedHashMap<String, ByteArray>()
    val devices = LinkedHashSet<String>()
    var quotaBytes = Long.MAX_VALUE

    override suspend fun putBlob(id: String, contentType: String, bytes: ByteArray) {
        require(Regex("^[A-Za-z0-9._-]{1,128}$").matches(id)) { "bad blob id $id" }
        if (blobs.filterKeys { it != id }.values.sumOf { it.size.toLong() } + bytes.size > quotaBytes) throw app.tsumugi.sync.SyncException("quota exceeded", 413)
        blobs[id] = bytes
    }

    override suspend fun getBlob(id: String): ByteArray? = blobs[id]
    override suspend fun deleteBlob(id: String) { blobs.remove(id) }
    override suspend fun deviceIds(): List<String> = devices.toList()
}

class RecordingsTest {
    private val clock = TestClock()
    private val fs = FakeFileSystem()
    private val server = FakeBlobStore()

    private inner class Device(val name: String, val sealer: Sealer? = null) {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val dataDir = "/$name".toPath()
        val recordings = RecordingStore(db, fs, dataDir, "app-$name", clock)
        val images = ImageStore(db, fs, dataDir, "app-$name", clock)
        val settings = DeviceSettings(db)
        val sync = RecordingSync(db, fs, recordings, images, settings, "app-$name", { server }, { "srv-$name" }, { sealer }, clock)

        init {
            server.devices += "srv-$name"
        }
    }

    @Test
    fun recordingsLiveOnDiskAndInTheIndex() = runTest {
        val d = Device("a")
        val pending = d.recordings.newRecording("m4a")
        assertTrue(pending.path.startsWith("/a/recordings/"))
        fs.write(pending.path.toPath()) { write(ByteArray(100) { 1 }) }
        val rec = d.recordings.register(pending, RecordingKind.SENTENCE, "猫が好き", durationMs = 1200, referenceKey = ReferenceClip.pack("dlg-1-3").key)
        assertEquals("audio/mp4", rec.mime)
        assertEquals(100, rec.sizeBytes)
        assertEquals(listOf(rec.id), d.recordings.recordingsFor(RecordingKind.SENTENCE, "猫が好き").map { it.id })

        val side = d.recordings.sideBySide(rec.id)!!
        assertEquals(ReferenceKind.PACK_AUDIO, side.reference!!.kind)
        assertEquals("dlg-1-3", side.reference!!.value)
        assertEquals(pending.path, side.learnerPath)

        // Never uploaded: a delete removes the row and the file outright.
        d.recordings.delete(rec.id)
        assertNull(d.recordings.recording(rec.id))
        assertFalse(fs.exists(pending.path.toPath()))
        assertNull(d.db.mediaQueries.recordingById(rec.id).executeAsOneOrNull())
    }

    @Test
    fun sideBySideResolvesAnotherRecordingAsReference() = runTest {
        val d = Device("a")
        val model = d.recordings.save(ByteArray(10), RecordingKind.FREE, null, 500)
        val mine = d.recordings.save(ByteArray(12), RecordingKind.SENTENCE, "x", 600, referenceKey = ReferenceClip.recording(model.id).key)
        val side = d.recordings.sideBySide(mine.id)!!
        assertEquals(d.recordings.pathOf(model), side.referencePath)
        assertEquals(ReferenceClip.tts("こんにちは"), ReferenceClip.parse("tts:こんにちは"))
        assertNull(ReferenceClip.parse("bogus"))
    }

    @Test
    fun syncIsOffByDefaultAndDoesNothing() = runTest {
        val a = Device("a")
        a.recordings.save(ByteArray(10), RecordingKind.FREE, null, 500)
        val result = a.sync.sync()
        assertFalse(result.enabled)
        assertTrue(server.blobs.isEmpty())
    }

    @Test
    fun recordingsAndPicturesReachTheOtherDeviceAndDeletesFollow() = runTest {
        val a = Device("a")
        val b = Device("b")
        a.sync.setEnabled(true)
        b.sync.setEnabled(true)
        val audio = ByteArray(300) { (it % 7).toByte() }
        val rec = a.recordings.save(audio, RecordingKind.CARD, "p:1", 900, extension = "m4a")
        val img = a.images.save(ByteArray(50) { 9 }, "jpg")

        val up = a.sync.sync()
        assertEquals(2, up.uploaded)
        assertNotNull(a.recordings.recording(rec.id)!!.syncedAt)

        val down = b.sync.sync()
        assertEquals(2, down.downloaded)
        val copy = b.recordings.recording(rec.id)!!
        assertEquals(RecordingKind.CARD, copy.kind)
        assertEquals("p:1", copy.ref)
        assertEquals("app-a", copy.originDevice)
        assertContentEquals(audio, fs.read(b.recordings.pathOf(copy).toPath()) { readByteArray() })
        assertNotNull(b.images.pathOf(img.id))

        // A second round moves nothing.
        assertEquals(0, b.sync.sync().downloaded)

        // Deleting on B deletes the blob, and A's copy follows on its next sync.
        b.recordings.delete(rec.id)
        b.sync.sync()
        assertFalse(server.blobs.keys.any { it == "rec-${rec.id}" })
        val after = a.sync.sync()
        assertEquals(1, after.deleted)
        assertNull(a.recordings.recording(rec.id))
        // …and it isn't downloaded again.
        assertEquals(0, b.sync.sync().downloaded)
    }

    @Test
    fun withEndToEndEncryptionBlobIdsAndBytesAreSealed() = runTest {
        val key = E2eKeys.derive("passphrase", E2eKeys.newSalt(), E2eKeys.Params(iterations = 1, memoryKiB = 64))
        val a = Device("a", E2eSealer(key))
        val b = Device("b", E2eSealer(key))
        a.sync.setEnabled(true)
        b.sync.setEnabled(true)
        val audio = "not a secret? it is".encodeToByteArray()
        val rec = a.recordings.save(audio, RecordingKind.FREE, "private", 100)
        a.sync.sync()
        assertTrue(server.blobs.keys.all { it.startsWith("e-") }, "ids are keyed hashes: ${server.blobs.keys}")
        assertTrue(server.blobs.values.none { it.decodeToString().contains("private") || it.contentEquals(audio) })
        b.sync.sync()
        assertContentEquals(audio, fs.read(b.recordings.pathOf(b.recordings.recording(rec.id)!!).toPath()) { readByteArray() })
    }

    @Test
    fun aQuotaErrorIsReportedAndTheRestStillSyncs() = runTest {
        val a = Device("a")
        a.sync.setEnabled(true)
        server.quotaBytes = 1_500
        a.recordings.save(ByteArray(900), RecordingKind.FREE, null, 1)
        a.recordings.save(ByteArray(900), RecordingKind.FREE, null, 1)
        val result = a.sync.sync()
        assertEquals(1, result.uploaded)
        assertEquals(1, result.failures.size)
        assertEquals(1, a.db.mediaQueries.recordingsToUpload("app-a").executeAsList().size, "the failed one is retried next time")
    }

    @Test
    fun personalCardsPutThePictureOnTheFront() = runTest {
        val d = Device("a")
        val srs = SrsRepository(d.db, "app-a", clock)
        val cards = PersonalCards(d.db, srs, d.recordings, d.images, clock)
        val img = d.images.save(ByteArray(20), "jpg")
        val voice = d.recordings.save(ByteArray(30), RecordingKind.FREE, null, 700)
        val id = cards.create("犬", reading = "いぬ", note = "our dog Pochi", imageId = img.id, audioRecordingId = voice.id)

        val item = srs.item(id)!!
        assertEquals(ItemKind.CUSTOM, item.kind)
        assertEquals(ItemSource.USER, item.source)
        assertEquals(img.id, cards.contextOf(item)!!.imageId)
        // The recording is filed as this card's audio.
        assertEquals(voice.id, d.recordings.recordingsFor(RecordingKind.CARD, id).single().id)

        val render = cards.render(SrsRepository.cardId(id, CardDirection.RECALL))!!
        assertEquals(d.images.pathOf(img), render.front.imagePath)
        assertNull(render.front.text, "the word is the answer, not the prompt")
        assertEquals("犬", render.back.text)
        assertEquals(d.recordings.pathOf(voice), render.back.audioPath)
        assertTrue(render.back.note!!.contains("Pochi"))
        assertTrue(render.missing.isEmpty())
    }

    @Test
    fun anyItemCanGetASelfRecordedAudioSide() = runTest {
        val d = Device("a")
        val srs = SrsRepository(d.db, "app-a", clock)
        val cards = PersonalCards(d.db, srs, d.recordings, d.images, clock)
        srs.addItems(listOf(NewItem("jmdict:1", ItemKind.VOCAB, "猫", "ねこ", listOf("cat"), listOf("ねこ"), ItemSource.USER, listOf(CardDirection.MEANING))))
        val voice = d.recordings.save(ByteArray(30), RecordingKind.ITEM, "jmdict:1", 700)
        val cardId = cards.addAudioSide("jmdict:1", voice.id)
        assertEquals(SrsRepository.cardId("jmdict:1", CardDirection.LISTENING), cardId)
        val render = cards.render(cardId)!!
        assertEquals(d.recordings.pathOf(voice), render.front.audioPath)
        assertNull(render.front.text)

        // On a device without the file (recordings sync off), the card says so and falls back to TTS.
        fs.delete(d.recordings.pathOf(voice).toPath())
        val missing = cards.render(cardId)!!
        assertNull(missing.front.audioPath)
        assertEquals("ねこ", missing.front.speakFallback)
        assertEquals(listOf("recording"), missing.missing)
    }
}
