package app.tsumugi.lyrics

import app.tsumugi.api.TranslationOutcome
import app.tsumugi.db.Lyrics_song
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.domain.ItemSource
import app.tsumugi.grammar.GrammarService
import app.tsumugi.media.PcmSource
import app.tsumugi.media.SubtitleGenerator
import app.tsumugi.media.SubtitleProgress
import app.tsumugi.platform.normalizeNfc
import app.tsumugi.reader.ReaderAnalyzer
import app.tsumugi.reader.ReaderToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** How a song's lines are timed. */
enum class LyricsTiming {
    /** Enhanced LRC: every word has its own time. */
    LRC_WORDS,

    /** Line-level LRC; words are spread over each line by morae. */
    LRC_LINES,

    /** Plain lyrics timed from Whisper segments ([LyricsAligner]). */
    ALIGNED,

    /** Plain lyrics, not aligned yet: read-along without highlighting. */
    NONE,
}

data class LyricsSong(
    val id: String,
    val title: String,
    val artist: String?,
    /** The learner's own audio file (platform handle). Nothing is streamed or downloaded (BRIEF_V2 §6.3). */
    val audioLocator: String,
    val mediaHash: String?,
    val timing: LyricsTiming,
    val lines: List<LyricLine>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val timed: Boolean get() = timing != LyricsTiming.NONE
}

data class LyricsSongSummary(val id: String, val title: String, val artist: String?, val timing: LyricsTiming, val updatedAt: Long)

/** A grammar point found in a line (reader grammar detection); [aiGenerated] when its write-up is LLM-drafted. */
data class LyricGrammarNote(val pointId: String, val title: String, val meaning: String, val jlpt: Int, val aiGenerated: Boolean)

/** A line broken into tappable words (dictionary lookups) with its grammar notes. */
data class LyricLineStudy(val lineIndex: Int, val text: String, val tokens: List<ReaderToken>, val grammar: List<LyricGrammarNote>)

/**
 * Lyrics and karaoke reading (BRIEF_V2 §6.3, DECISIONS D-163). The learner imports an audio file they own plus
 * `.lrc` (line or word timed) or plain lyrics; plain lyrics are timed from Whisper segments ([align]). Songs are
 * device-local: the audio never leaves the device, lyrics are usually someone else's copyrighted text, and without
 * the audio file a song is of no use on another device. [exportLrc] gives the learner their timings and
 * translations back as a file (rule 6).
 *
 * - [analyzer]: the reader's analyzer (tokens for word spans and taps, grammar detection); null without the packs.
 * - [translate]: `translate_sentence` with the configured model (AI-generated, labeled).
 */
class LyricsService(
    private val db: TsumugiDatabase,
    private val analyzer: suspend () -> ReaderAnalyzer?,
    private val subtitles: SubtitleGenerator,
    private val translate: suspend (String) -> TranslationOutcome,
    private val grammar: suspend () -> GrammarService? = { null },
    private val clock: Clock = Clock.System,
) {
    private val q get() = db.immersionQueries
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val linesSerializer = ListSerializer(LyricLine.serializer())

    /** Imports lyrics for an audio file. [lyrics] is the .lrc or .txt content; LRC is detected automatically. */
    @Throws(Exception::class)
    suspend fun importSong(title: String, artist: String?, audioLocator: String, lyrics: String, mediaHash: String? = null): LyricsSong {
        val now = clock.now().toEpochMilliseconds()
        val (lines, timing, lrcTitle, lrcArtist) = if (Lrc.isLrc(lyrics)) {
            val file = Lrc.parse(lyrics)
            val raw = file.lines
            val out = ArrayList<LyricLine>()
            raw.forEachIndexed { i, l ->
                if (l.text.isBlank()) return@forEachIndexed // an empty stamp only ends the previous line
                val end = raw.getOrNull(i + 1)?.startMs?.takeIf { it > l.startMs }
                val words = if (l.words.isNotEmpty()) lrcWords(l, end) else wordsFor(l.text, l.startMs, end)
                out += LyricLine(l.text, l.startMs, end, words)
            }
            Parsed(out, if (file.hasWordTiming) LyricsTiming.LRC_WORDS else LyricsTiming.LRC_LINES, file.title, file.artist)
        } else {
            val plain = lyrics.removePrefix("﻿").replace("\r\n", "\n").split('\n').map { normalizeNfc(it.trim()) }.filter { it.isNotEmpty() }
            Parsed(plain.map { LyricLine(it) }, LyricsTiming.NONE, null, null)
        }
        require(lines.isNotEmpty()) { "No lyric lines found" }
        val id = Uuid.random().toString()
        val songTitle = title.trim().ifEmpty { lrcTitle ?: "Untitled song" }
        val songArtist = artist?.trim()?.ifEmpty { null } ?: lrcArtist
        withContext(Dispatchers.IO) {
            q.putSong(id, songTitle, songArtist, audioLocator, mediaHash, timing.name, encode(lines), now, now)
        }
        return song(id)!!
    }

    /**
     * Times plain lyrics (or re-times any song) from Whisper segments of the audio: [SubtitleGenerator] transcribes
     * [source] (cached by [mediaHash]), then [LyricsAligner] maps lines onto the segments. Throws
     * [app.tsumugi.media.SubtitleGenerationException] when no speech model is set up.
     */
    @Throws(Exception::class)
    suspend fun align(songId: String, mediaHash: String, source: PcmSource, onProgress: (SubtitleProgress) -> Unit = {}): LyricsSong {
        val song = song(songId) ?: throw IllegalArgumentException("no song $songId")
        val generated = subtitles.generate(mediaHash, source, "ja", onProgress)
        val times = withContext(Dispatchers.Default) { LyricsAligner.align(song.lines.map { it.text }, generated.cues) }
        val lines = song.lines.mapIndexed { i, l ->
            val t = times[i] ?: return@mapIndexed l.copy(startMs = null, endMs = null, words = emptyList())
            l.copy(startMs = t.first, endMs = t.second, words = wordsFor(l.text, t.first, t.second))
        }
        save(songId, lines, LyricsTiming.ALIGNED)
        return song(songId)!!
    }

    @Throws(Exception::class)
    suspend fun song(id: String): LyricsSong? = withContext(Dispatchers.IO) { q.songById(id).executeAsOneOrNull()?.toSong() }

    @Throws(Exception::class)
    suspend fun songs(): List<LyricsSongSummary> = withContext(Dispatchers.IO) {
        q.songList().executeAsList().map { LyricsSongSummary(it.id, it.title, it.artist, timingOf(it.timing), it.updated_at) }
    }

    @Throws(Exception::class)
    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { q.deleteSong(id) }
    }

    /** The learner's own English for a line (empty clears it). */
    @Throws(Exception::class)
    suspend fun setTranslation(songId: String, lineIndex: Int, english: String): LyricsSong = editLine(songId, lineIndex) {
        val text = english.trim()
        if (text.isEmpty()) it.copy(translation = null, translationSource = null, translationEngine = null)
        else it.copy(translation = text, translationSource = LyricLine.SOURCE_USER, translationEngine = null)
    }

    /**
     * Translates a line with the configured model and stores it labeled `llm` (rule 10). A learner's own
     * translation is never overwritten. Returns the outcome so the UI can show "set up a model" or the error.
     */
    @Throws(Exception::class)
    suspend fun translateLine(songId: String, lineIndex: Int): TranslationOutcome {
        val song = song(songId) ?: throw IllegalArgumentException("no song $songId")
        val line = song.lines.getOrNull(lineIndex) ?: throw IllegalArgumentException("no line $lineIndex")
        if (line.translationSource == LyricLine.SOURCE_USER && line.translation != null) {
            return TranslationOutcome(line.translation, "", "", null, null, needsSetup = false)
        }
        val outcome = translate(line.text)
        if (outcome.engine != null && outcome.translation.isNotBlank()) {
            editLine(songId, lineIndex) {
                it.copy(translation = outcome.translation.trim(), translationSource = LyricLine.SOURCE_LLM, translationEngine = outcome.engine)
            }
        }
        return outcome
    }

    /** Words to tap and grammar notes for one line (reader grammar detection). Empty without the packs. */
    @Throws(Exception::class)
    suspend fun lineStudy(songId: String, lineIndex: Int): LyricLineStudy? {
        val song = song(songId) ?: return null
        val line = song.lines.getOrNull(lineIndex) ?: return null
        val a = analyzer() ?: return LyricLineStudy(lineIndex, line.text, emptyList(), emptyList())
        val paragraph = a.paragraph(line.text, line.text.indices)
        val tokens = paragraph.sentences.flatMap { it.tokens }
        val ids = paragraph.sentences.flatMap { it.grammarPointIds }.distinct()
        val g = if (ids.isEmpty()) null else grammar()
        val notes = ids.mapNotNull { id ->
            val p = g?.point(id)?.point ?: return@mapNotNull null
            LyricGrammarNote(p.id, p.title, p.meaning, p.jlpt, p.source == ItemSource.LLM)
        }
        return LyricLineStudy(lineIndex, line.text, tokens, notes)
    }

    /** Sets the words hidden in cloze mode for a line ([spans] replace the line's current picks). */
    @Throws(Exception::class)
    suspend fun setCloze(songId: String, lineIndex: Int, spans: List<ClozeSpan>): LyricsSong = editLine(songId, lineIndex) { line ->
        withCloze(line, spans)
    }

    /**
     * Picks one word per line to hide (the longest dictionary word; about one line in [everyNthLine]), with
     * readings from the analyzer. Needs the packs; returns the song unchanged without them.
     */
    @Throws(Exception::class)
    suspend fun autoCloze(songId: String, everyNthLine: Int = 1): LyricsSong {
        val song = song(songId) ?: throw IllegalArgumentException("no song $songId")
        val a = analyzer() ?: return song
        val lines = song.lines.mapIndexed { i, line ->
            if (i % everyNthLine.coerceAtLeast(1) != 0) return@mapIndexed line
            val tokens = a.paragraph(line.text, line.text.indices).sentences.flatMap { it.tokens }.filter { it.isWord }
            val pick = tokens.maxByOrNull { it.surface.length } ?: return@mapIndexed line
            line.copy(cloze = listOf(ClozeSpan(pick.start, pick.end, pick.reading)))
        }
        save(songId, lines, song.timing)
        return song(songId)!!
    }

    /** A cloze session over the song's current picks. */
    @Throws(Exception::class)
    suspend fun clozeSession(songId: String): ClozeSession? = song(songId)?.let { ClozeSession(it.lines) }

    /** The song as LRC (word times as enhanced LRC), for export. */
    @Throws(Exception::class)
    suspend fun exportLrc(songId: String): String? = song(songId)?.let { Lrc.write(it.lines, it.title, it.artist) }

    // --- internals ---------------------------------------------------------------------------------------------

    private data class Parsed(val lines: List<LyricLine>, val timing: LyricsTiming, val title: String?, val artist: String?)

    private fun withCloze(line: LyricLine, spans: List<ClozeSpan>): LyricLine =
        line.copy(cloze = spans.filter { it.start in 0 until it.end && it.end <= line.text.length }.distinctBy { it.start }.sortedBy { it.start })

    private fun lrcWords(line: LrcLine, lineEnd: Long?): List<LyricWord> {
        var at = 0
        return line.words.mapIndexed { i, w ->
            val start = at
            at += w.text.length
            val end = line.words.getOrNull(i + 1)?.startMs ?: lineEnd ?: (w.startMs + 1_000)
            LyricWord(start, at, w.startMs, maxOf(end, w.startMs))
        }
    }

    /** Word spans from the analyzer (the whole line as one word without it), timed by morae when [end] is known. */
    private suspend fun wordsFor(text: String, start: Long?, end: Long?): List<LyricWord> {
        if (start == null || end == null || end <= start) return emptyList()
        val tokens = analyzer()?.let { a -> runCatching { a.paragraph(text, text.indices).sentences.flatMap { it.tokens } }.getOrNull() }
        val spans = tokens?.filter { t -> t.surface.any { it.isLetterOrDigit() } }?.map { Triple(it.start, it.end, it.reading) }
            ?: listOf(Triple(0, text.length, null))
        return Karaoke.distribute(text, spans, start, end)
    }

    private suspend fun editLine(songId: String, lineIndex: Int, change: (LyricLine) -> LyricLine): LyricsSong {
        val song = song(songId) ?: throw IllegalArgumentException("no song $songId")
        require(lineIndex in song.lines.indices) { "no line $lineIndex" }
        val lines = song.lines.toMutableList()
        lines[lineIndex] = change(lines[lineIndex])
        save(songId, lines, song.timing)
        return song(songId)!!
    }

    private suspend fun save(songId: String, lines: List<LyricLine>, timing: LyricsTiming) = withContext(Dispatchers.IO) {
        q.setSongLines(encode(lines), timing.name, clock.now().toEpochMilliseconds(), songId)
        Unit
    }

    private fun encode(lines: List<LyricLine>) = json.encodeToString(linesSerializer, lines)

    private fun timingOf(s: String) = runCatching { LyricsTiming.valueOf(s) }.getOrDefault(LyricsTiming.NONE)

    private fun Lyrics_song.toSong() = LyricsSong(
        id, title, artist, audio_locator, media_hash, timingOf(timing),
        runCatching { json.decodeFromString(linesSerializer, lines) }.getOrDefault(emptyList()), created_at, updated_at,
    )
}
