package app.tsumugi.android.platform

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cuts [startMs, endMs) of a media file's audio into an AAC .m4a at [outPath] with Media3 Transformer (G-04 "save
 * clip to SRS", the ClipDraft path). Transformer re-encodes, so any source codec works (MediaMuxer could only copy
 * AAC). Returns the clip's duration in ms. Cancelling the coroutine cancels the export.
 */
object ClipCutter {
    @OptIn(UnstableApi::class)
    suspend fun cut(context: Context, source: Uri, startMs: Long, endMs: Long, outPath: String): Long = withContext(Dispatchers.Main) {
        val main = Handler(Looper.getMainLooper())
        suspendCancellableCoroutine { cont ->
            val item = MediaItem.Builder()
                .setUri(source)
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                .build()
            val edited = EditedMediaItem.Builder(item).setRemoveVideo(true).build()
            val transformer = Transformer.Builder(context.applicationContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(exportResult.durationMs.takeIf { it > 0 } ?: (endMs - startMs))
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            transformer.start(edited, outPath)
            cont.invokeOnCancellation { main.post { runCatching { transformer.cancel() } } }
        }
    }
}

/**
 * Podcast episode downloads (G-04) as WorkManager jobs, so they survive leaving the screen. HttpURLConnection with
 * explicit bounds (rule 13): 5 s to connect, 120 s between bytes. Bookkeeping goes through the shared
 * [app.tsumugi.media.PodcastService] (QUEUED → DOWNLOADING → DONE / FAILED).
 */
object PodcastDownloads {
    private fun name(id: String) = "podcast:$id"

    suspend fun start(context: Context, episodeId: String) {
        val graph = (context.applicationContext as TsumugiApplication).graph
        graph.podcasts.queue(episodeId)
        val request = OneTimeWorkRequestBuilder<PodcastDownloadWorker>()
            .setInputData(workDataOf(PodcastDownloadWorker.EPISODE to episodeId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(episodeId), ExistingWorkPolicy.REPLACE, request)
    }

    suspend fun cancel(context: Context, episodeId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(episodeId))
        (context.applicationContext as TsumugiApplication).graph.podcasts.deleteDownload(episodeId)
    }
}

class PodcastDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(EPISODE) ?: return Result.failure()
        val podcasts = (applicationContext as TsumugiApplication).graph.podcasts
        val episode = podcasts.episode(id) ?: return Result.failure()
        val target = File(podcasts.targetFile(id))
        val part = File(target.path + ".part")
        return try {
            podcasts.markDownloading(id, 0)
            withContext(Dispatchers.IO) { download(episode.audioUrl, part) { bytes -> podcasts.markDownloading(id, bytes) } }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't move the download into place")
            podcasts.markDownloaded(id)
            Result.success()
        } catch (e: CancellationException) {
            part.delete()
            withContext(NonCancellable) { runCatching { podcasts.markFailed(id, "Cancelled") } }
            throw e
        } catch (e: Exception) {
            part.delete()
            podcasts.markFailed(id, e.readable())
            Result.failure()
        }
    }

    private suspend fun download(url: String, out: File, onBytes: suspend (Long) -> Unit) {
        var current = URL(url)
        var connection: HttpURLConnection? = null
        // Follow up to 5 redirects by hand: HttpURLConnection won't follow http → https.
        for (attempt in 0 until 5) {
            val c = current.openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 120_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Tsumugi (podcast download)")
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                current = URL(current, location)
                c.disconnect()
                continue
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("The server answered $code")
            }
            connection = c
            break
        }
        val c = connection ?: throw IOException("Too many redirects")
        try {
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var reported = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (isStopped) throw CancellationException("stopped")
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        total += n
                        if (total - reported >= 512 * 1024) {
                            reported = total
                            onBytes(total)
                        }
                    }
                    onBytes(total)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val EPISODE = "episode"
    }
}
