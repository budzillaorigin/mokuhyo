package app.tsumugi.media

import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.dictionary.DictionaryFixture
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.recordings.ImageStore
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.RecordingStore
import app.tsumugi.settings.DeviceSettings
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BRIEF_V2 §6.2: the sentence bank over the learner's media, merged sentence search, the online source, mining. */
class SentenceBankTest {
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
    private val dictionary = DictionaryRepository(DictionaryFixture.create())
    private val bank = SentenceBank(db, { dictionary.tokenize(it) }, clock)

    private val cues = listOf(
        Cue(1_000, 2_500, "猫が好きです。"),
        Cue(3_000, 5_000, "寿司を食べなかった。"),
        Cue(6_000, 7_000, "学校に行く。"),
        Cue(8_000, 9_000, "トトロ！"),
    )

    @Test
    fun indexesCuesAndFindsInflectedWordsByDictionaryEntry() = runTest {
        val progress = ArrayList<IndexProgress>()
        val media = bank.index("m1", "My Show 01", MediaKind.VIDEO, "file:///show01.mkv", cues, CueSource.FILE) { progress += it }
        assertEquals(4, media.cueCount)
        assertEquals(IndexProgress(4, 4), progress.last())

        val hits = bank.linesForEntry(DictionaryFixture.TABERU)
        val hit = hits.single()
        assertEquals("寿司を食べなかった。", hit.japanese)
        assertEquals(SentenceSource.LIBRARY, hit.source)
        assertTrue(hit.hasHighlight)
        assertTrue(hit.japanese.substring(hit.highlightStart, hit.highlightEnd).startsWith("食べ"))
        assertEquals(ClipSpan(2_750, 5_250, 4_000), hit.clip)
        assertEquals("file:///show01.mkv", hit.locator)
        assertEquals(1, hit.cueIndex)

        // Free text: the query's words by lemma; then a substring fallback for what the tokenizer can't split.
        assertEquals(listOf("寿司を食べなかった。"), bank.search("食べる").map { it.japanese })
        assertEquals(listOf("トトロ！"), bank.search("トトロ").map { it.japanese })
        assertTrue(bank.search("   ").isEmpty())

        // Re-indexing replaces; removing forgets.
        bank.index("m1", "My Show 01", MediaKind.AUDIO, null, cues.take(1), CueSource.GENERATED)
        assertTrue(bank.linesForEntry(DictionaryFixture.TABERU).isEmpty())
        assertNull(bank.linesForEntry(DictionaryFixture.NEKO).single().clip!!.thumbnailMs, "audio has no frame")
        bank.remove("m1")
        assertTrue(bank.indexed().isEmpty())
        assertTrue(bank.search("猫").isEmpty())
    }

    private class FakeSource(var hits: List<SentenceHit> = emptyList(), var fail: Boolean = false) : ExampleSource {
        override val id = "fake"
        override val name = "Fake"
        var calls = 0
        override suspend fun search(word: String, limit: Int): List<SentenceHit> {
            calls++
            if (fail) throw IllegalStateException("offline")
            return hits
        }
    }

    @Test
    fun sentenceSearchPutsTheLibraryFirstThenTatoebaAndTheOnlineSourceOnlyWhenOn() = runTest {
        bank.index("m1", "Show", MediaKind.VIDEO, null, cues, CueSource.FILE)
        val remote = SentenceHit(SentenceSource.IMMERSION_KIT, "猫だ。", "It's a cat.", 0, 1, remoteId = "r1")
        val source = FakeSource(listOf(remote))
        val settings = DeviceSettings(db)
        val online = OnlineExamples(source, settings)
        val search = SentenceSearch(bank, { dictionary }, online)

        val off = search.forEntry(DictionaryFixture.NEKO, "猫", "ねこ")
        assertEquals(listOf(SentenceSource.LIBRARY, SentenceSource.TATOEBA), off.all.map { it.source })
        assertEquals(OnlineExamplesResult.Disabled, off.online)
        assertEquals(0, source.calls, "nothing is fetched while the switch is off")
        val tatoeba = off.tatoeba.single()
        assertEquals("猫が好きです。", tatoeba.japanese)
        assertEquals("I like cats.", tatoeba.english)
        assertEquals(0 to 1, tatoeba.highlightStart to tatoeba.highlightEnd)

        online.setEnabled(true)
        val on = search.forEntry(DictionaryFixture.NEKO, "猫", "ねこ")
        assertEquals(listOf(SentenceSource.LIBRARY, SentenceSource.TATOEBA, SentenceSource.IMMERSION_KIT), on.all.map { it.source })
        val again = online.search("猫")
        assertIs<OnlineExamplesResult.Found>(again)
        assertTrue(again.fromSessionCache)
        assertEquals(1, source.calls, "the session cache answers repeats")
        assertNull(db.immersionQueries.mediaIndexById("r1").executeAsOneOrNull(), "online results are never stored")

        source.fail = true
        assertIs<OnlineExamplesResult.Failed>(online.search("犬"))
        online.setEnabled(false)
        assertEquals(OnlineExamplesResult.Disabled, online.search("猫"))
    }

    @Test
    fun locatesWordsAndInflectedStemsInTatoebaLines() {
        assertEquals(3 to 5, SentenceSearch.locate("寿司を食べる。", "食べる", "たべる").let { it.first to it.first + 2 })
        assertEquals(3 to 5, SentenceSearch.locate("寿司を食べた。", "食べる", "たべる"))
        assertEquals(0 to 2, SentenceSearch.locate("ねこだ", "猫", "ねこ"))
        assertEquals(-1 to -1, SentenceSearch.locate("犬だ", "猫", "ねこ"))
    }

    @Test
    fun parsesImmersionKitResponsesAndCallsTheV2Endpoint() = runTest {
        val body = """
            {"category_count":{"anime":1},"examples":[
              {"id":"anime_my_neighbor_totoro_000000172","sentence":"座って食べなさい。","translation":"Sit down and eat!",
               "word_list":["座っ","て","食べ","なさい","。"],"matched_indexes":[{"index":2,"length":2}],
               "image":"https://us-southeast-1.linodeobjects.com/immersionkit/media/anime/My%20Neighbor%20Totoro/media/a.jpg",
               "sound":"https://us-southeast-1.linodeobjects.com/immersionkit/media/anime/My%20Neighbor%20Totoro/media/a.mp3",
               "title":"my_neighbor_totoro"},
              {"id":"x","sentence":""}
            ]}
        """.trimIndent()
        val hits = ImmersionKitSource.parse(body, 10)
        val h = hits.single()
        assertEquals("食べ", h.japanese.substring(h.highlightStart, h.highlightEnd))
        assertEquals("My Neighbor Totoro", h.mediaTitle)
        assertEquals("Sit down and eat!", h.english)
        assertTrue(h.audioUrl!!.endsWith("a.mp3"))

        var url = ""
        val engine = MockEngine { req ->
            url = req.url.toString()
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals(1, ImmersionKitSource(engine).search("食べる", 5).size)
        assertTrue(url.startsWith("https://apiv2.immersionkit.com/search?"), url)
        assertTrue("showUrlInMedia=true" in url)
    }

    @Test
    fun minesALineIntoASentenceOrWordCardWithClipAndFrame() = runTest {
        val fs = FakeFileSystem()
        val srs = SrsRepository(db, "dev", clock)
        val recordings = RecordingStore(db, fs, "/data".toPath(), "dev", clock)
        val images = ImageStore(db, fs, "/data".toPath(), "dev", clock)
        val miner = SentenceMiner(db, srs, recordings, images, clock)

        val draft = miner.mineLine(
            MineKind.SENTENCE, "m1", "My Show 01", MediaKind.VIDEO, 3_000, 5_000, "寿司を食べなかった。", 3, 6,
            translation = "I didn't eat the sushi.",
        )
        assertEquals(2_750L to 5_250L, draft.startMs to draft.endMs)
        assertEquals(4_000L, draft.thumbnailMs)
        val image = assertNotNull(draft.image)
        fs.write(draft.audio.path.toPath()) { write(ByteArray(64) { 1 }) }
        fs.write(image.path.toPath()) { write(ByteArray(32) { 2 }) }
        val card = assertNotNull(miner.attachMedia(draft, audioDurationMs = 2_500, imageWritten = true))
        assertEquals("寿司を" to "食べな", card.before to card.highlighted)
        assertEquals("かった。", card.after)
        assertEquals("I didn't eat the sushi.", card.translation)
        assertNotNull(card.audioPath)
        assertNotNull(card.imagePath)
        assertTrue(card.missing.isEmpty())
        val item = srs.item(draft.itemId)!!
        assertEquals(ItemKind.SENTENCE, item.kind)
        assertEquals(CardDirection.RECOGNITION, srs.cardsForItems(listOf(item.id)).single().direction)
        assertEquals(draft.clipId, db.mediaQueries.clipById(draft.clipId).executeAsOne().id)
        assertEquals(1, recordings.recordingsFor(RecordingKind.CLIP, draft.clipId).size)

        // A word card from an audio-only file: no frame, the extraction failed, TTS fallback.
        val word = miner.mineLine(
            MineKind.VOCAB, "m2", "Podcast", MediaKind.AUDIO, 0, 1_000, "猫が好きです。", 0, 1,
            reading = "ねこ", meanings = listOf("cat"), entryId = DictionaryFixture.NEKO,
        )
        assertNull(word.image)
        val render = assertNotNull(miner.attachMedia(word, 0, imageWritten = false))
        assertEquals(MineKind.VOCAB, render.kind)
        assertEquals("猫", render.word)
        assertEquals(listOf("cat"), render.meanings)
        assertNull(render.audioPath)
        assertEquals(listOf("recording"), render.missing)
        assertEquals(setOf(CardDirection.MEANING, CardDirection.READING), srs.cardsForItems(listOf(word.itemId)).map { it.direction }.toSet())
    }
}
