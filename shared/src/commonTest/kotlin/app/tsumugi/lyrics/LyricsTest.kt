package app.tsumugi.lyrics

import app.tsumugi.api.TranslationOutcome
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.media.Cue
import app.tsumugi.media.SubtitleGenerator
import app.tsumugi.srs.Verdict
import app.tsumugi.testing.TestClock
import app.tsumugi.testing.inMemoryDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BRIEF_V2 §6.3: LRC parsing, karaoke position, alignment from segments, cloze checking, the lyrics service. */
class LyricsTest {

    @Test
    fun parsesSimpleLrcWithTagsOffsetAndRepeatedStamps() {
        val lrc = """
            [ti:さくら]
            [ar:テスト]
            [offset:+500]
            [00:10.00]さくら さくら
            [00:15.5]やよいの空は
            [00:20.123][01:00.00]見わたす限り
            [00:25:50]
            not a lyric line
        """.trimIndent()
        assertTrue(Lrc.isLrc(lrc))
        val f = Lrc.parse(lrc)
        assertEquals("さくら", f.title)
        assertEquals("テスト", f.artist)
        assertEquals(500, f.offsetMs)
        assertEquals(listOf(9_500L, 15_000L, 19_623L, 25_000L, 59_500L), f.lines.map { it.startMs })
        assertEquals(listOf("さくら さくら", "やよいの空は", "見わたす限り", "", "見わたす限り"), f.lines.map { it.text })
        assertFalse(f.hasWordTiming)
        assertFalse(Lrc.isLrc("さくら さくら\nやよいの空は"))
    }

    @Test
    fun parsesEnhancedWordLevelLrc() {
        val f = Lrc.parse("[00:12.00]<00:12.00>今日<00:12.80>は <00:13.20>晴れ<00:14.00>")
        val line = f.lines.single()
        assertEquals("今日は 晴れ", line.text)
        assertEquals(listOf("今日", "は ", "晴れ"), line.words.map { it.text })
        assertEquals(listOf(12_000L, 12_800L, 13_200L), line.words.map { it.startMs })
        assertTrue(f.hasWordTiming)
    }

    @Test
    fun karaokeFindsLineAndWordAndWritesBackToLrc() {
        val lines = listOf(
            LyricLine("今日は", 1_000, 2_000, listOf(LyricWord(0, 2, 1_000, 1_600), LyricWord(2, 3, 1_600, 2_000))),
            LyricLine("晴れ", 3_000, null),
            LyricLine("untimed"),
        )
        assertEquals(-1, Karaoke.at(lines, 500).lineIndex)
        val p = Karaoke.at(lines, 1_300)
        assertEquals(0, p.lineIndex)
        assertEquals(0, p.wordIndex)
        assertTrue(p.active)
        assertEquals(0.5, p.wordProgress, 1e-9)
        assertEquals(1, Karaoke.at(lines, 1_700).wordIndex)
        val gap = Karaoke.at(lines, 2_500)
        assertEquals(0, gap.lineIndex)
        assertFalse(gap.active)
        assertEquals(1, Karaoke.at(lines, 3_100).lineIndex)
        assertEquals(8_000L, Karaoke.lineEnd(lines, 1))

        val written = Lrc.write(lines.take(2), "t")
        assertEquals("[ti:t]\n[00:01.00]<00:01.00>今日<00:01.60>は\n[00:03.00]晴れ\n", written)
        assertEquals(listOf("今日", "は"), Lrc.parse(written).lines.first().words.map { it.text })
    }

    @Test
    fun distributesALineOverWordsByMorae() {
        // きょう = 2 morae (きょ・う), は = 1, いい = 2: 5 morae over 1 000 ms.
        val words = Karaoke.distribute("今日はいい", listOf(Triple(0, 2, "きょう"), Triple(2, 3, null), Triple(3, 5, null)), 0, 1_000)
        assertEquals(listOf(0L, 400L, 600L), words.map { it.startMs })
        assertEquals(listOf(400L, 600L, 1_000L), words.map { it.endMs })
    }

    @Test
    fun alignsPlainLyricsToWhisperSegments() {
        val lines = listOf("さくら さくら", "やよいの空は", "見わたす限り")
        val segments = listOf(Cue(10_000, 14_000, "さくらさくら"), Cue(15_000, 19_000, "やよいのそらは"), Cue(20_000, 24_000, "みわたすかぎり"))
        val times = LyricsAligner.align(lines, segments)
        val (s0, e0) = times[0]!!
        assertTrue(s0 in 9_500..10_500 && e0 in 13_500..14_600, "line 1 $s0..$e0")
        val (s1, e1) = times[1]!!
        assertTrue(s1 in 14_500..15_500 && e1 in 18_000..19_500, "line 2 $s1..$e1")
        // Whisper wrote the third line in kana: の / わたす / り still anchor it inside the last segment.
        val (s2, e2) = times[2]!!
        assertTrue(s2 >= 19_000 && e2 <= 24_500 && e2 > s2, "line 3 $s2..$e2")
        assertTrue(e0 <= s1 && e1 <= s2, "no overlaps")
        assertEquals(listOf(null), LyricsAligner.align(listOf("x"), emptyList()))
    }

    @Test
    fun unanchoredLinesAreSpreadBetweenTheirNeighbours() {
        val times = LyricsAligner.align(listOf("あいうえお", "ＸＸＸ", "ＹＹＹ", "かきくけこ"), listOf(Cue(0, 1_000, "あいうえお"), Cue(5_000, 6_000, "かきくけこ")))
        val a = times[0]!!
        val d = times[3]!!
        val b = times[1]!!
        val c = times[2]!!
        assertTrue(b.first >= a.second - 1 && c.first >= b.first && c.second <= d.first, "$a $b $c $d")
    }

    @Test
    fun clozeIsKanaKanjiTolerantAndHidesUntilSung() {
        assertEquals(Verdict.CORRECT, ClozeSession.check("空", "そら", "空"))
        assertEquals(Verdict.CORRECT, ClozeSession.check("空", "そら", "そら"))
        assertEquals(Verdict.CORRECT, ClozeSession.check("空", "そら", "ソラ"))
        assertEquals(Verdict.CORRECT, ClozeSession.check("空", "そら", "sora"))
        assertEquals(Verdict.CLOSE, ClozeSession.check("見わたす", "みわたす", "みわたず"))
        assertEquals(Verdict.WRONG, ClozeSession.check("空", "そら", "うみ"))

        val lines = listOf(
            LyricLine("やよいの空は", 1_000, 3_000, listOf(LyricWord(0, 4, 1_000, 2_000), LyricWord(4, 5, 2_000, 2_500), LyricWord(5, 6, 2_500, 3_000)), cloze = listOf(ClozeSpan(4, 5, "そら"))),
        )
        val session = ClozeSession(lines)
        assertEquals("やよいの＿は", session.masked(0))
        assertTrue(session.update(2_200).isEmpty(), "not sung yet")
        val due = session.update(2_500)
        assertEquals(listOf("空"), due.map { it.answer })
        assertEquals(ClozeState.DUE, session.blanks.single().state)
        assertTrue(session.answer(due.single().id, "そら").accepted)
        assertEquals("やよいの空は", session.masked(0))
        assertEquals(1 to 1, session.score)
    }

    private fun service(db: TsumugiDatabase, translation: TranslationOutcome) = LyricsService(
        db, analyzer = { null }, subtitles = SubtitleGenerator(db, { null }), translate = { translation }, clock = TestClock(),
    )

    @Test
    fun importsLrcAndPlainLyricsKeepsTranslationsLabeledAndExports() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val ai = TranslationOutcome("Cherry blossoms", "", "", "qwen", null, needsSetup = false)
        val lyrics = service(db, ai)
        val song = lyrics.importSong("", null, "file:///music/sakura.m4a", "[ti:さくら]\n[00:01.00]さくら さくら\n[00:04.00]\n[00:05.00]やよいの空は")
        assertEquals("さくら", song.title)
        assertEquals(LyricsTiming.LRC_LINES, song.timing)
        assertEquals(listOf("さくら さくら", "やよいの空は"), song.lines.map { it.text })
        assertEquals(4_000L, song.lines[0].endMs, "an empty stamp ends the previous line")
        assertEquals(1, song.lines[0].words.size, "no analyzer: the whole line is one word")

        val ai1 = lyrics.translateLine(song.id, 0)
        assertEquals("Cherry blossoms", ai1.translation)
        val stored = lyrics.song(song.id)!!.lines[0]
        assertTrue(stored.aiTranslated)
        assertEquals("qwen", stored.translationEngine)
        lyrics.setTranslation(song.id, 1, "The spring sky")
        lyrics.translateLine(song.id, 1)
        val mine = lyrics.song(song.id)!!.lines[1]
        assertEquals("The spring sky", mine.translation)
        assertFalse(mine.aiTranslated, "a learner's translation is never overwritten")

        lyrics.setCloze(song.id, 1, listOf(ClozeSpan(4, 5, "そら"), ClozeSpan(9, 12)))
        assertEquals(listOf(ClozeSpan(4, 5, "そら")), lyrics.song(song.id)!!.lines[1].cloze, "out-of-range picks are dropped")
        assertEquals(1, lyrics.clozeSession(song.id)!!.blanks.size)

        val plain = lyrics.importSong("Song", "Me", "file:///music/a.mp3", "一行目\n\n二行目\n")
        assertEquals(LyricsTiming.NONE, plain.timing)
        assertNull(plain.lines[0].startMs)
        assertEquals(2, lyrics.songs().size)

        val exported = assertNotNull(lyrics.exportLrc(song.id))
        assertTrue(exported.startsWith("[ti:さくら]\n[00:01.00]<00:01.00>さくら さくら"), exported)
        lyrics.delete(plain.id)
        assertEquals(1, lyrics.songs().size)
    }

    @Test
    fun noModelLeavesTheLineUntranslated() = runTest {
        val db = TsumugiDatabase(inMemoryDriver(TsumugiDatabase.Schema))
        val lyrics = service(db, TranslationOutcome("", "", "", null, "No AI model is set up.", needsSetup = true))
        val song = lyrics.importSong("s", null, "x", "一行目")
        assertTrue(lyrics.translateLine(song.id, 0).needsSetup)
        assertNull(lyrics.song(song.id)!!.lines[0].translation)
    }
}
