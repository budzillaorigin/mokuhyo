package app.tsumugi.android.platform

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.tsumugi.api.AppGraph
import app.tsumugi.media.ClipSpan
import app.tsumugi.media.Cue
import app.tsumugi.recordings.ReferenceClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The model audio for [text]: the pre-rendered clip for [key] when its audio pack is installed (rule 20, D-090),
 * else the TTS player. One call so every screen that has a key gets pack audio without knowing about packs.
 */
suspend fun clipPlayerFor(context: Context, graph: AppGraph, voices: Voices, key: String?, text: String): ClipPlayer {
    val path = key?.let { k -> withContext(Dispatchers.IO) { graph.audio.clip(k) } }
    return if (key != null && path != null) FileClipPlayer(context, path.toString(), ReferenceClip.pack(key)) else TtsClipPlayer(context, voices, text)
}

/**
 * Sentence-bank clips (BRIEF_V2 §6.2, D-160): the audio of a line and a frame of the video, extracted on demand
 * from the learner's own file with the Phase 10 tools (Media3 Transformer, MediaMetadataRetriever) and cached in
 * cache/bank. Nothing here decides what a clip is; the span comes from the shared [ClipSpan].
 */
object BankClips {
    private fun dir(context: Context) = File(context.cacheDir, "bank").apply { mkdirs() }

    private fun name(mediaId: String, span: ClipSpan) = "${mediaId.take(24).filter { it.isLetterOrDigit() }}-${span.startMs}-${span.endMs}"

    /** The line's audio as a cached .m4a, cut the first time it's asked for. Throws when the file can't be read. */
    suspend fun audio(context: Context, locator: String, mediaId: String, span: ClipSpan): File {
        val out = File(dir(context), name(mediaId, span) + ".m4a")
        if (out.length() > 0) return out
        val part = File(out.path + ".part.m4a")
        part.delete()
        ClipCutter.cut(context, Uri.parse(locator), span.startMs, span.endMs, part.path)
        if (!part.renameTo(out)) throw java.io.IOException("Couldn't store the clip")
        return out
    }

    /** A frame at [atMs] as a cached JPEG, or null for audio-only files or when the frame can't be decoded. */
    suspend fun frame(context: Context, locator: String, mediaId: String, atMs: Long, maxPx: Int = 480): File? = withContext(Dispatchers.IO) {
        val out = File(dir(context), "${mediaId.take(24).filter { it.isLetterOrDigit() }}-f$atMs-$maxPx.jpg")
        if (out.length() > 0) return@withContext out
        val bitmap = grab(context, Uri.parse(locator), atMs, maxPx) ?: return@withContext null
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        out
    }

    /** Writes the frame at [atMs] of [uri] to [path] (a pending image); false when there is no picture. */
    suspend fun writeFrame(context: Context, uri: Uri, atMs: Long, path: String, maxPx: Int = 1080): Boolean = withContext(Dispatchers.IO) {
        val bitmap = grab(context, uri, atMs, maxPx) ?: return@withContext false
        runCatching { FileOutputStream(path).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) } }.isSuccess
    }

    private fun grab(context: Context, uri: Uri, atMs: Long, maxPx: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = maxPx.toFloat() / maxOf(frame.width, frame.height)
            if (scale < 1f) Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true) else frame
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

/**
 * Cues back to SRT text, for the shared APIs that take a subtitle file (coverage, 1T cues, media decks) when the
 * player has cues rather than the file (generated subtitles, or cues reloaded from the sentence bank).
 */
fun cuesToSrt(cues: List<Cue>): String = buildString {
    fun t(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    cues.forEachIndexed { i, c ->
        append(i + 1).append('\n')
        append(t(c.startMs)).append(" --> ").append(t(c.endMs)).append('\n')
        append(c.text.replace("\n\n", "\n")).append("\n\n")
    }
}
