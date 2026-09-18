package app.tsumugi.media

import app.tsumugi.ai.Sha256
import app.tsumugi.ai.SpeechRecognizer
import app.tsumugi.db.TsumugiDatabase
import app.tsumugi.platform.normalizeNfc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import kotlin.coroutines.coroutineContext
import kotlin.time.Clock

/**
 * Decoded audio of one media file, as the recognizer needs it: 16 kHz mono PCM. The platform decodes (AVAsset /
 * MediaExtractor) one window at a time, so an hour-long episode is never in memory at once.
 */
interface PcmSource {
    /** Length of the audio in milliseconds. */
    val durationMs: Long

    /** Samples of [startMs, endMs) as 16 kHz mono 16-bit PCM (shorter at the end of the file). */
    @Throws(Exception::class)
    suspend fun read(startMs: Long, endMs: Long): ShortArray
}

/** A [PcmSource] over samples already in memory (tests, short clips). */
class InMemoryPcm(private val samples: ShortArray) : PcmSource {
    override val durationMs: Long get() = samples.size * 1000L / SAMPLE_RATE

    override suspend fun read(startMs: Long, endMs: Long): ShortArray {
        val from = (startMs * SAMPLE_RATE / 1000).toInt().coerceIn(0, samples.size)
        val to = (endMs * SAMPLE_RATE / 1000).toInt().coerceIn(from, samples.size)
        return samples.copyOfRange(from, to)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** How far subtitle generation got: [windowsDone] of [windowsTotal] 30-second windows. */
data class SubtitleProgress(val windowsDone: Int, val windowsTotal: Int) {
    val fraction: Double get() = if (windowsTotal == 0) 1.0 else windowsDone.toDouble() / windowsTotal
}

/** Generated subtitles: the cues, their SRT text, which engine made them, and whether they came from the cache. */
data class GeneratedSubtitles(val cues: List<Cue>, val srt: String, val engine: String, val fromCache: Boolean)

class SubtitleGenerationException(message: String) : Exception(message)

/**
 * Subtitles from audio through the configured [SpeechRecognizer] (on-device Whisper by default; BRIEF_V2 G-04,
 * DECISIONS D-113). The audio is cut into [windowMs] windows that overlap by [overlapMs] (Whisper works on 30 s
 * windows; the overlap keeps a word cut at a boundary whole in one of them). Segment times are shifted to the
 * file's timeline, and each overlap is split at its midpoint: a segment belongs to the window whose half of the
 * overlap holds its midpoint, so nothing is duplicated or lost.
 *
 * Progress is reported per window; cancelling the calling coroutine stops after the current window (and the
 * Whisper bridge is cancelled with it). Results are cached by the media's content key ([MediaHash]) and language,
 * so a second open is instant and nothing is cached for a cancelled run.
 */
class SubtitleGenerator(
    private val db: TsumugiDatabase,
    private val recognizer: suspend () -> SpeechRecognizer?,
    private val windowMs: Long = WINDOW_MS,
    private val overlapMs: Long = OVERLAP_MS,
    private val clock: Clock = Clock.System,
) {
    init {
        require(windowMs > 0 && overlapMs in 0 until windowMs)
    }

    private val q get() = db.mediaQueries

    /** Cached subtitles for [mediaHash], or null. */
    @Throws(Exception::class)
    suspend fun cached(mediaHash: String, language: String = "ja"): GeneratedSubtitles? = withContext(Dispatchers.IO) {
        q.cachedSubtitles(mediaHash, language).executeAsOneOrNull()?.let { GeneratedSubtitles(Subtitles.parse(it.srt), it.srt, it.engine, fromCache = true) }
    }

    /** Generates (or returns cached) subtitles for the media with content key [mediaHash]. */
    @Throws(Exception::class)
    suspend fun generate(
        mediaHash: String,
        source: PcmSource,
        language: String = "ja",
        onProgress: (SubtitleProgress) -> Unit = {},
    ): GeneratedSubtitles {
        cached(mediaHash, language)?.let {
            onProgress(SubtitleProgress(1, 1))
            return it
        }
        val stt = recognizer() ?: throw SubtitleGenerationException("No speech model is set up: download Whisper in Settings → AI")
        val windows = windows(source.durationMs)
        onProgress(SubtitleProgress(0, windows.size))
        val cues = ArrayList<Cue>()
        var engine = ""
        windows.forEachIndexed { i, w ->
            coroutineContext.ensureActive()
            val pcm = source.read(w.first, w.last)
            if (pcm.isNotEmpty()) {
                val transcript = stt.transcribe(pcm, language)
                engine = transcript.engine
                val keepFrom = if (i == 0) Long.MIN_VALUE else w.first + overlapMs / 2
                val keepTo = if (i == windows.lastIndex) Long.MAX_VALUE else windows[i + 1].first + overlapMs / 2
                for (s in transcript.segments) {
                    val text = normalizeNfc(s.text.trim())
                    if (text.isEmpty()) continue
                    val start = (w.first + s.startMs).coerceAtLeast(w.first)
                    val end = (w.first + s.endMs).coerceIn(start + 1, w.last)
                    val mid = (start + end) / 2
                    if (mid >= keepFrom && mid < keepTo) cues += Cue(start, end, text)
                }
            }
            onProgress(SubtitleProgress(i + 1, windows.size))
        }
        val merged = cues.sortedBy { it.startMs }.let(::dropRepeats)
        val srt = Srt.write(merged)
        withContext(Dispatchers.IO) { q.putSubtitles(mediaHash, language, srt, engine, clock.now().toEpochMilliseconds()) }
        return GeneratedSubtitles(merged, srt, engine, fromCache = false)
    }

    @Throws(Exception::class)
    suspend fun clear(mediaHash: String) = withContext(Dispatchers.IO) { q.deleteSubtitles(mediaHash) }

    /** [start, end) windows covering [durationMs]: step = window − overlap, the last one cut at the end. */
    fun windows(durationMs: Long): List<LongRange> {
        if (durationMs <= 0) return emptyList()
        val step = windowMs - overlapMs
        val out = ArrayList<LongRange>()
        var start = 0L
        while (true) {
            val end = minOf(start + windowMs, durationMs)
            out += start..end
            if (end >= durationMs) break
            start += step
        }
        return out
    }

    /** Whisper sometimes repeats a line across the overlap with slightly different times: keep the first. */
    private fun dropRepeats(cues: List<Cue>): List<Cue> {
        val out = ArrayList<Cue>()
        for (c in cues) {
            val prev = out.lastOrNull()
            if (prev != null && prev.text == c.text && c.startMs < prev.endMs + overlapMs) continue
            out += c
        }
        return out
    }

    companion object {
        const val WINDOW_MS = 30_000L
        const val OVERLAP_MS = 5_000L
    }
}

/** SRT output for generated subtitles (the parser lives in [Subtitles]). */
object Srt {
    fun write(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { i, c ->
            append(i + 1).append('\n')
            append(time(c.startMs)).append(" --> ").append(time(c.endMs)).append('\n')
            append(c.text).append("\n\n")
        }
    }

    fun time(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val f = ms % 1000
        return "${pad(h, 2)}:${pad(m, 2)}:${pad(s, 2)},${pad(f, 3)}"
    }

    private fun pad(v: Long, n: Int) = v.toString().padStart(n, '0')
}

/**
 * Content key of a media file for caches (subtitles, transcripts): SHA-256 over the size and three 1 MiB samples
 * (start, middle, end). Hashing a whole 2 GB video on a phone takes too long for a cache key; renaming or
 * re-importing the same file still hits the cache, and any real edit changes size or a sample (D-113).
 */
object MediaHash {
    const val SAMPLE = 1L shl 20

    @Throws(Exception::class)
    suspend fun of(fs: FileSystem, path: String): String = withContext(Dispatchers.IO) { ofBlocking(fs, path.toPath()) }

    internal fun ofBlocking(fs: FileSystem, path: Path): String {
        val size = fs.metadata(path).size ?: throw IllegalArgumentException("unknown size: $path")
        val sha = Sha256()
        sha.update(size.toString().encodeToByteArray())
        fs.openReadOnly(path).use { handle ->
            val offsets = if (size <= 3 * SAMPLE) listOf(0L) else listOf(0L, size / 2 - SAMPLE / 2, size - SAMPLE)
            val buffer = ByteArray(64 * 1024)
            for (offset in offsets) {
                var pos = offset
                val end = if (size <= 3 * SAMPLE) size else offset + SAMPLE
                while (pos < end) {
                    val n = handle.read(pos, buffer, 0, minOf(buffer.size.toLong(), end - pos).toInt())
                    if (n <= 0) break
                    sha.update(buffer, 0, n)
                    pos += n
                }
            }
        }
        return "m1-" + sha.hexDigest()
    }
}
