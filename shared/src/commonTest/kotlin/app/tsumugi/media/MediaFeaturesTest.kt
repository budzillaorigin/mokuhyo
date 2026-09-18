package app.tsumugi.media

import app.tsumugi.ai.SpeechRecognizer
import app.tsumugi.ai.Transcript
import app.tsumugi.ai.TranscriptSegment
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.CardDirection
import app.tsumugi.domain.ItemKind
import app.tsumugi.recordings.RecordingKind
import app.tsumugi.recordings.RecordingStore
import app.tsumugi.srs.SrsRepository
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A recognizer that "hears" scripted lines: each line is spoken over a known span of the file, and a window
 * transcription returns the lines whose span starts inside it, with window-relative times (as Whisper does).
 */
class FakeRecognizer(private val lines: List<Cue>, private val windowStarts: MutableList<Long> = mutableListOf()) : SpeechRecognizer {
    var calls = 0
    var onCall: suspend () -> Unit = {}
    /** The caller tells us where each window starts (the PCM itself carries no timeline). */
    var nextWindowStart = 0L

    override suspend fun transcribe(pcm16kMono: ShortArray, language: String): Transcript {
        calls++
        onCall()
        val start = windowStarts.removeFirstOrNull() ?: nextWindowStart
        val end = start + pcm16kMono.size * 1000L / 16_000
        val segs = lines.filter { it.startMs >= start && it.startMs < end }.map {
            TranscriptSegment(it.startMs - start, minOf(it.endMs, end) - start, it.text)
        }
        return Transcript(segs.joinToString("") { it.text }, segs, "fake whisper")
    }
}

/** A PCM source that tells the fake recognizer where each read starts. */
private class TimelinePcm(override val durationMs: Long, private val recognizer: FakeRecognizer) : PcmSource {
    val reads = ArrayList<LongRange>()
    override suspend fun read(startMs: Long, endMs: Long): ShortArray {
        reads += startMs..endMs
        recognizer.nextWindowStart = startMs
        return ShortArray(((minOf(endMs, durationMs) - startMs) * 16).toInt())
    }
}

class MediaFeaturesTest {
    private val clock = TestClock()
    private val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))

    private val script = listOf(
        Cue(1_000, 4_000, "こんにちは、今日はいい天気ですね。"),
        Cue(27_000, 29_000, "窓の外を見てください。"), // inside the first overlap [25 s, 30 s)
        Cue(40_000, 43_000, "雨が降るかもしれません。"),
        Cue(52_500, 54_000, "傘を持って行きましょう。"), // inside the second overlap [50 s, 55 s)
        Cue(61_000, 63_000, "じゃあ、また明日。"),
    )

    @Test
    fun windowsAreThirtySecondsWithFiveSecondsOverlap() {
        val gen = SubtitleGenerator(db, { null })
        assertEquals(listOf(0L..30_000L, 25_000L..55_000L, 50_000L..65_000L), gen.windows(65_000))
        assertEquals(listOf(0L..10_000L), gen.windows(10_000))
        assertTrue(gen.windows(0).isEmpty())
    }

    @Test
    fun overlappingWindowsGiveEachLineExactlyOnceOnTheFileTimeline() = runTest {
        val stt = FakeRecognizer(script)
        val gen = SubtitleGenerator(db, { stt }, clock = clock)
        val progress = ArrayList<SubtitleProgress>()
        val result = gen.generate("m1-abc", TimelinePcm(65_000, stt)) { progress += it }
        assertEquals(script.map { it.text }, result.cues.map { it.text })
        assertEquals(27_000, result.cues[1].startMs)
        assertEquals(52_500, result.cues[3].startMs)
        assertEquals(listOf(0, 1, 2, 3), progress.map { it.windowsDone })
        assertTrue(progress.all { it.windowsTotal == 3 })
        assertFalse(result.fromCache)
        assertTrue(result.srt.startsWith("1\n00:00:01,000 --> 00:00:04,000\nこんにちは"))
        // SRT round-trips through the player's parser.
        assertEquals(result.cues, Subtitles.parse(result.srt))
    }

    @Test
    fun resultsAreCachedByContentKey() = runTest {
        val stt = FakeRecognizer(script)
        val gen = SubtitleGenerator(db, { stt }, clock = clock)
        gen.generate("m1-abc", TimelinePcm(65_000, stt))
        val calls = stt.calls
        val again = gen.generate("m1-abc", TimelinePcm(65_000, stt))
        assertTrue(again.fromCache)
        assertEquals(calls, stt.calls, "no second transcription")
        assertEquals(5, again.cues.size)
        assertNull(gen.cached("m1-other"))
    }

    @Test
    fun cancellingStopsAndCachesNothing() = runTest {
        val stt = FakeRecognizer(script)
        val gen = SubtitleGenerator(db, { stt }, clock = clock)
        stt.onCall = { if (stt.calls == 2) throw CancellationException("user left") }
        val job = async { gen.generate("m1-cancel", TimelinePcm(65_000, stt)) }
        assertFailsWith<CancellationException> { job.await() }
        assertNull(gen.cached("m1-cancel"))
        assertEquals(2, stt.calls)
    }

    @Test
    fun withoutASpeechModelTheErrorIsHonest() = runTest {
        val gen = SubtitleGenerator(db, { null })
        assertFailsWith<SubtitleGenerationException> { gen.generate("m1", InMemoryPcm(ShortArray(16_000))) }
    }

    @Test
    fun mediaHashIgnoresTheNameButNotTheContent() = runTest {
        val fs = FakeFileSystem()
        fs.createDirectories("/m".toPath())
        val big = ByteArray(5 shl 20) { (it * 31).toByte() }
        fs.write("/m/a.mp4".toPath()) { write(big) }
        fs.write("/m/b.mp4".toPath()) { write(big) }
        val changed = big.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        fs.write("/m/c.mp4".toPath()) { write(changed) }
        assertEquals(MediaHash.of(fs, "/m/a.mp4"), MediaHash.of(fs, "/m/b.mp4"))
        assertTrue(MediaHash.of(fs, "/m/a.mp4") != MediaHash.of(fs, "/m/c.mp4"))
    }

    @Test
    fun aSavedClipBecomesAListeningCardWithItsAudio() = runTest {
        val fs = FakeFileSystem()
        val srs = SrsRepository(db, "dev", clock)
        val recordings = RecordingStore(db, fs, "/data".toPath(), "dev", clock)
        val clips = ClipService(db, srs, recordings, clock)
        val draft = clips.saveClip("media-1", "Episode 3", 40_000, 43_000, "雨が降るかもしれません。", translation = "It might rain.")
        assertEquals(39_750, draft.startMs)
        assertEquals(43_250, draft.endMs)
        val item = srs.item(draft.clip.itemId)!!
        assertEquals(ItemKind.LISTENING, item.kind)
        assertEquals(listOf("It might rain."), item.meanings)
        assertNotNull(srs.card(SrsRepository.cardId(item.id, CardDirection.LISTENING)))
        assertEquals("media-1", clips.contextOf(item.context)!!.mediaId)

        // The platform cuts the segment into the draft's file, then attaches it.
        fs.write(draft.audio.path.toPath()) { write(ByteArray(64)) }
        val clip = clips.attachAudio(draft.clip.id, draft.audio, 3_500)
        assertEquals(draft.audio.id, clip.recordingId)
        assertEquals(clip.id, recordings.recording(draft.audio.id)!!.ref)
        assertEquals(RecordingKind.CLIP, recordings.recording(draft.audio.id)!!.kind)
        assertEquals(listOf(clip.id), clips.clipsFor("media-1").map { it.id })
    }

    @Test
    fun speedRangeIsPointSevenToOnePointTwo() {
        assertEquals(0.7, MediaPlayback.clampSpeed(0.5))
        assertEquals(1.2, MediaPlayback.clampSpeed(2.0))
        assertEquals(1.1, MediaPlayback.step(1.0, up = true))
        assertEquals(0.7, MediaPlayback.step(0.7, up = false))
        assertEquals(1.2, MediaPlayback.step(1.2, up = true))
        assertEquals(MediaPlayback.SPEED_MIN, MediaPlayback.SPEEDS.first())
        assertEquals(MediaPlayback.SPEED_MAX, MediaPlayback.SPEEDS.last())
    }

    @Test
    fun hideSubtitleQuizTypesPicksAndReveals() {
        val typed = SubtitleQuiz(script, QuizMode.TYPE)
        val i = typed.questionIndices.first()
        assertEquals(QuizPhase.HIDDEN, typed.phase(i))
        val kanaAnswer = typed.answer(i, "こんにちは 今日はいい天気ですね")
        assertTrue(kanaAnswer.correct, "punctuation and spaces don't count: ${kanaAnswer.score}")
        assertEquals(QuizPhase.ANSWERED, typed.phase(i))
        assertFalse(typed.answer(i, "さようなら").correct)
        assertEquals(script[i], typed.reveal(i))
        assertEquals(QuizPhase.REVEALED, typed.phase(i))

        val pick = SubtitleQuiz(script, QuizMode.PICK, seed = 7)
        val q = pick.question(2)
        assertEquals(SubtitleQuiz.CHOICES, q.choices.size)
        assertTrue(script[2].text in q.choices)
        assertEquals(q.choices.toSet().size, q.choices.size)
        assertTrue(pick.answer(2, script[2].text).correct)
        assertFalse(pick.answer(2, q.choices.first { it != script[2].text }).correct)
        assertEquals(1 to 2, pick.score)
    }

    @Test
    fun podcastFeedsKeepEpisodesAndDownloadState() = runTest {
        val fs = FakeFileSystem()
        var xml = FEED
        val podcasts = PodcastService(db, fs, "/data".toPath(), { xml }, clock)
        val p = podcasts.subscribe("https://example.org/feed.xml")
        assertEquals("やさしい日本語ポッドキャスト", p.title)
        assertEquals("Example Host", p.author)
        assertEquals("https://example.org/cover.jpg", p.imageUrl)
        val eps = podcasts.episodes(p.id)
        assertEquals(listOf("第2回 買い物", "第1回 自己紹介"), eps.map { it.title })
        assertEquals("https://example.org/ep2.mp3", eps[0].audioUrl)
        assertEquals(1_234_567L, eps[0].lengthBytes)
        assertEquals((12 * 60 + 5) * 1000L, eps[0].durationMs)
        assertEquals(DownloadState.NONE, eps[0].downloadState)

        // The platform downloads into targetFile and reports back.
        podcasts.queue(eps[0].id)
        assertEquals(listOf(eps[0].id), podcasts.pendingDownloads().map { it.id })
        val target = podcasts.targetFile(eps[0].id)
        assertTrue(target.endsWith(".mp3"))
        podcasts.markDownloading(eps[0].id, 1_000)
        assertEquals(1_000.0 / 1_234_567, podcasts.episode(eps[0].id)!!.downloadFraction!!, 1e-9)
        fs.write(target.toPath()) { write(ByteArray(2048)) }
        val done = podcasts.markDownloaded(eps[0].id)
        assertEquals(DownloadState.DONE, done.downloadState)
        assertEquals(target, done.localPath)
        assertNotNull(done.mediaHash)

        // A refresh adds the new episode on top and keeps the download.
        xml = FEED.replace("<item>", NEW_ITEM + "<item>")
        val refreshed = podcasts.refresh(p.id)
        assertEquals(3, refreshed.size)
        assertEquals("第3回 天気", refreshed[0].title)
        assertEquals(DownloadState.DONE, refreshed.first { it.id == eps[0].id }.downloadState)

        // Transcript through the subtitle generator, keyed by the downloaded file's content.
        val stt = FakeRecognizer(listOf(Cue(1_000, 2_000, "今日は買い物に行きます。")))
        val transcript = podcasts.transcript(eps[0].id, SubtitleGenerator(db, { stt }, clock = clock), TimelinePcm(10_000, stt))
        assertEquals("今日は買い物に行きます。", transcript.cues.single().text)
        assertFailsWith<Exception> { podcasts.transcript(eps[1].id, SubtitleGenerator(db, { stt }), TimelinePcm(10_000, stt)) }

        podcasts.unsubscribe(p.id)
        assertTrue(podcasts.podcasts().isEmpty())
        assertFalse(fs.exists(target.toPath()))
    }

    @Test
    fun aFeedWithoutAudioIsRejected() = runTest {
        val podcasts = PodcastService(db, FakeFileSystem(), "/data".toPath(), { "<rss><channel><title>t</title><item><title>a</title><link>https://x/1</link></item></channel></rss>" })
        assertFailsWith<Exception> { podcasts.subscribe("https://x/feed") }
    }

    private companion object {
        const val NEW_ITEM = """<item><title>第3回 天気</title><guid>ep3</guid><enclosure url="https://example.org/ep3.mp3" type="audio/mpeg" length="99"/></item>"""
        const val FEED = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
<channel>
<title>やさしい日本語ポッドキャスト</title>
<itunes:author>Example Host</itunes:author>
<itunes:image href="https://example.org/cover.jpg"/>
<item>
<title>第2回 買い物</title>
<guid isPermaLink="false">ep2</guid>
<pubDate>Tue, 08 Sep 2026 06:00:00 +0000</pubDate>
<itunes:duration>12:05</itunes:duration>
<enclosure url="https://example.org/ep2.mp3" length="1234567" type="audio/mpeg"/>
<description><![CDATA[<p>買い物の会話</p>]]></description>
</item>
<item>
<title>第1回 自己紹介</title>
<guid isPermaLink="false">ep1</guid>
<enclosure url="https://example.org/ep1.mp3" length="1000" type="audio/mpeg"/>
</item>
</channel>
</rss>"""
    }
}
