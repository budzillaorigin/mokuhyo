package app.tsumugi.android.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.tsumugi.ai.DownloadProgress
import app.tsumugi.ai.ModelInfo
import app.tsumugi.android.MainActivity
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Background model downloads (BRIEF_V2 F-13, DECISIONS D-081). Each download is a unique WorkManager job that runs as
 * a foreground worker with a progress notification, so it keeps going when the learner leaves the screen or the app.
 * The worker collects the shared [app.tsumugi.ai.ModelManager.download] flow (which writes and hashes on IO and
 * resumes from `.part` files); cancelling the job pauses the download.
 */
object ModelDownloads {
    private const val TAG = "model-download"
    private const val CHANNEL = "downloads"
    internal const val KEY_MODEL = "model"

    private val _progress = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())

    /** Latest progress per model id, from the worker in this process. */
    val progress: StateFlow<Map<String, DownloadProgress>> = _progress.asStateFlow()

    private fun name(modelId: String) = "$TAG:$modelId"

    fun start(context: Context, model: ModelInfo) {
        _progress.update { it - model.id }
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(KEY_MODEL to model.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .addTag(name(model.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(model.id), ExistingWorkPolicy.KEEP, request)
    }

    /** Pauses: the partial file stays and the next start resumes from it. */
    fun cancel(context: Context, modelId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(name(modelId))
        _progress.update { it - modelId }
    }

    /** Ids of models whose download job is queued or running (survives process death, unlike [progress]). */
    fun active(context: Context): Flow<Set<String>> =
        WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { infos ->
            infos.filter { !it.state.isFinished }
                .flatMap { info -> info.tags.filter { it.startsWith("$TAG:") }.map { it.removePrefix("$TAG:") } }
                .toSet()
        }

    internal fun report(modelId: String, p: DownloadProgress) = _progress.update { it + (modelId to p) }

    internal fun clear(modelId: String) = _progress.update { it - modelId }

    internal fun notification(context: Context, model: ModelInfo, p: DownloadProgress?, cancel: PendingIntent): android.app.Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.download_title, model.name))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, context.getString(R.string.ai_pause), cancel)
        when (p) {
            is DownloadProgress.Downloading -> builder
                .setProgress(1000, (p.fraction * 1000).toInt(), false)
                .setContentText(context.getString(R.string.ai_progress, gb(p.bytesDone), gb(p.bytesTotal)))
            is DownloadProgress.Verifying -> builder.setProgress(0, 0, true).setContentText(context.getString(R.string.ai_verifying, p.file))
            else -> builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    internal fun notificationId(modelId: String) = 0x7500 + (modelId.hashCode() and 0xff)
}

class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val modelId = inputData.getString(ModelDownloads.KEY_MODEL) ?: return Result.failure()
        val graph = (applicationContext as TsumugiApplication).graph
        val manager = graph.ai.models ?: return Result.failure()
        val model = manager.model(modelId) ?: return Result.failure()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        setForeground(info(model, null, cancel))
        var last: DownloadProgress? = null
        var notifiedAt = 0L
        try {
            manager.download(model).collect { p ->
                last = p
                ModelDownloads.report(modelId, p)
                val now = SystemClock.elapsedRealtime()
                if (p !is DownloadProgress.Downloading || now - notifiedAt >= 1_000) {
                    notifiedAt = now
                    setForeground(info(model, p, cancel))
                }
            }
        } catch (e: CancellationException) {
            ModelDownloads.clear(modelId)
            throw e
        }
        // A failure stays in [ModelDownloads.progress] so the settings screen can show it (e.g. not enough storage).
        return if (last is DownloadProgress.Done) Result.success() else Result.failure()
    }

    private fun info(model: ModelInfo, p: DownloadProgress?, cancel: PendingIntent): ForegroundInfo {
        val notification = ModelDownloads.notification(applicationContext, model, p, cancel)
        val id = ModelDownloads.notificationId(model.id)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }
}

internal fun gb(bytes: Long): String = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)
