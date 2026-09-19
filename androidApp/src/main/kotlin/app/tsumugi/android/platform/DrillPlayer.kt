package app.tsumugi.android.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import app.tsumugi.android.MainActivity
import app.tsumugi.android.R
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.audio.AudioSet
import app.tsumugi.practice.DrillCursor
import app.tsumugi.practice.DrillItem
import app.tsumugi.practice.DrillPlan
import app.tsumugi.practice.DrillPlayback
import app.tsumugi.practice.DrillSet
import app.tsumugi.practice.DrillStep
import app.tsumugi.practice.DrillStepKind
import app.tsumugi.practice.DrillTiming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The hands-free speaking-drill player (BRIEF_V2 §6.10, Swotter format): prompt → pause → model answer → repeat pause,
 * item after item, with the timing from the shared [DrillPlayback] plan. It lives at process level (not in a screen),
 * so playback continues with the screen off or the app in the background; [DrillPlaybackService] keeps the process in
 * the foreground with a wake lock while it plays and exposes play/pause/next/previous to the notification, the lock
 * screen and headset buttons through a framework [MediaSession].
 *
 * The English cue uses the platform TTS (en-US); the answer is the pre-rendered clip ([DrillItem.audioKey], rule 20)
 * or the Japanese TTS / VOICEVOX fallback, through [Voices].
 */
object DrillPlayer {
    data class State(
        val set: DrillSet? = null,
        val plan: DrillPlan? = null,
        val stepIndex: Int = 0,
        val playing: Boolean = false,
        val finished: Boolean = false,
        val timing: DrillTiming = DrillTiming.DEFAULT,
        /** Items whose answer length came from an installed clip (the rest are estimates and use TTS). */
        val clips: Int = 0,
        /** The English cue couldn't be spoken (no en-US voice): the screen shows it instead. */
        val noEnglishVoice: Boolean = false,
    ) {
        val step: DrillStep? get() = plan?.steps?.getOrNull(stepIndex)
        val itemIndex: Int get() = step?.itemIndex ?: (set?.items?.size ?: 0)
        val item: DrillItem? get() = set?.items?.getOrNull(itemIndex)
        val remainingMs: Long get() = plan?.steps?.drop(stepIndex)?.sumOf { it.durationMs } ?: 0
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appContext: Context? = null
    private var voices: Voices? = null
    private var english: EnglishVoice? = null
    private var cursor: DrillCursor? = null
    private var job: Job? = null
    /** Bumped whenever the running loop must stop acting (pause, skip, stop), so a late resume can't advance. */
    private var generation = 0
    private var answerMs: Map<String, Long> = emptyMap()

    private fun ensure(context: Context) {
        val app = context.applicationContext
        if (appContext == null) appContext = app
        if (voices == null) voices = Voices(app, (app as TsumugiApplication).graph)
        if (english == null) english = EnglishVoice(app)
    }

    /**
     * Prepares [set] with [timing]. Reopening the set that is already loaded keeps its position (and playback); a
     * different set stops the current one. Clip lengths are read from the installed audio packs off the main thread.
     */
    suspend fun load(context: Context, set: DrillSet, timing: DrillTiming) {
        ensure(context)
        val current = _state.value
        if (current.set?.id == set.id && current.plan != null) {
            if (current.timing != timing) setTiming(timing)
            return
        }
        stop()
        answerMs = clipLengths(context, set)
        val plan = DrillPlayback.plan(set, timing) { answerMs[it.audioKey] }
        cursor = DrillCursor(plan)
        _state.value = State(set, plan, 0, playing = false, timing = timing, clips = set.items.count { it.audioKey in answerMs })
    }

    /** New pause settings; the plan is rebuilt and the current item restarts from its prompt. */
    fun setTiming(timing: DrillTiming) {
        val s = _state.value
        val set = s.set ?: return
        val wasPlaying = s.playing
        halt()
        val plan = DrillPlayback.plan(set, timing) { answerMs[it.audioKey] }
        val start = plan.steps.indexOfFirst { it.itemIndex == s.itemIndex && it.kind == DrillStepKind.PROMPT }.coerceAtLeast(0)
        cursor = DrillCursor(plan, start)
        _state.value = s.copy(plan = plan, timing = timing, stepIndex = start, finished = false, playing = wasPlaying)
        if (wasPlaying) run()
    }

    fun play(context: Context) {
        ensure(context)
        val c = cursor ?: return
        if (_state.value.playing) return
        if (c.finished) cursor = DrillCursor(c.plan)
        _state.value = _state.value.copy(playing = true, finished = false, stepIndex = cursor!!.index)
        DrillPlaybackService.start(context)
        run()
    }

    fun pause() {
        halt()
        _state.value = _state.value.copy(playing = false)
    }

    fun toggle(context: Context) = if (_state.value.playing) pause() else play(context)

    /** Next item (headset "next" too). */
    fun next() = move { it.skipItem() }

    /** Restart this item, or the previous one when already at its prompt. */
    fun previous() = move { it.previousItem() }

    /** Unloads the set and ends playback (and the service). */
    fun stop() {
        halt()
        cursor = null
        _state.value = State(timing = _state.value.timing)
    }

    private fun move(action: (DrillCursor) -> Unit) {
        val c = cursor ?: return
        val wasPlaying = _state.value.playing
        halt()
        action(c)
        _state.value = _state.value.copy(stepIndex = c.index, finished = c.finished, playing = wasPlaying && !c.finished)
        if (wasPlaying && !c.finished) run()
    }

    private fun halt() {
        generation++
        job?.cancel()
        job = null
        voices?.stop()
        english?.stop()
    }

    private fun run() {
        val c = cursor ?: return
        val gen = ++generation
        job = scope.launch {
            try {
                while (!c.finished && gen == generation) {
                    val step = c.current ?: break
                    _state.value = _state.value.copy(stepIndex = c.index)
                    perform(c.plan.set.items[step.itemIndex], step)
                    if (gen != generation) return@launch
                    c.advance()
                }
                if (gen == generation && c.finished) {
                    _state.value = _state.value.copy(stepIndex = c.index, playing = false, finished = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An audio failure must not leave the player "playing" silently forever.
                if (gen == generation) _state.value = _state.value.copy(playing = false)
            }
        }
    }

    private suspend fun perform(item: DrillItem, step: DrillStep) {
        when (step.kind) {
            DrillStepKind.PROMPT -> {
                val spoken = english?.say(item.promptEn) == true
                if (!spoken) {
                    _state.value = _state.value.copy(noEnglishVoice = true)
                    delay(step.durationMs)
                }
            }
            DrillStepKind.ANSWER -> voices?.sayClip(item.audioKey, item.answerJa) ?: delay(step.durationMs)
            DrillStepKind.ANSWER_PAUSE, DrillStepKind.REPEAT_PAUSE, DrillStepKind.GAP -> delay(step.durationMs)
        }
    }

    /** Real answer lengths from the installed packs' indexes (AudioClipInfo.ms); missing clips fall back to estimates. */
    private suspend fun clipLengths(context: Context, set: DrillSet): Map<String, Long> = withContext(Dispatchers.IO) {
        val graph = (context.applicationContext as TsumugiApplication).graph
        val out = HashMap<String, Long>()
        set.items.mapNotNull { AudioSet.ofKey(it.audioKey) }.distinct().forEach { audioSet ->
            val index = runCatching { graph.audio.index(audioSet) }.getOrNull() ?: return@forEach
            set.items.forEach { item ->
                val ms = index.clips[item.audioKey]?.ms ?: 0
                if (ms > 0 && graph.audio.clip(item.audioKey) != null) out[item.audioKey] = ms
            }
        }
        out
    }
}

/** The English cue voice: the platform TTS in en-US, awaited until the utterance ends. */
private class EnglishVoice(context: Context) {
    private val ready = CompletableDeferred<Boolean>()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        val ok = status == TextToSpeech.SUCCESS && runCatching { tts.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE }.getOrDefault(false)
        ready.complete(ok)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
        })
    }

    /** Speaks [text]; false when no English voice is available. */
    suspend fun say(text: String): Boolean {
        if (text.isBlank()) return true
        if (!ready.await()) return false
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return false
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            tts.stop()
            throw e
        } finally {
            pending.remove(id)
        }
        return true
    }

    fun stop() {
        tts.stop()
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }
}

/**
 * Keeps drill playback alive with the screen off: a `mediaPlayback` foreground service with a partial wake lock (so
 * the silences keep time), a notification with previous / play-pause / next, and a framework [MediaSession] for the
 * lock screen and headset buttons. It follows [DrillPlayer.state]: foreground while playing; when paused it leaves a
 * detached notification with a play button and stops; when the set ends or is stopped it removes the notification.
 * Without the notification permission the service still runs; only the notification is hidden.
 */
class DrillPlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.drillset_channel), NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "tsumugi-drills").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = DrillPlayer.play(this@DrillPlaybackService)
                override fun onPause() = DrillPlayer.pause()
                override fun onSkipToNext() = DrillPlayer.next()
                override fun onSkipToPrevious() = DrillPlayer.previous()
                override fun onStop() = DrillPlayer.stop()
            })
            isActive = true
        }
        scope.launch { DrillPlayer.state.collect { render(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground(), whatever the action.
        goForeground(DrillPlayer.state.value)
        when (intent?.action) {
            ACTION_PLAY -> DrillPlayer.play(this)
            ACTION_PAUSE -> DrillPlayer.pause()
            ACTION_NEXT -> DrillPlayer.next()
            ACTION_PREVIOUS -> DrillPlayer.previous()
            ACTION_STOP -> DrillPlayer.stop()
        }
        render(DrillPlayer.state.value)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun render(state: DrillPlayer.State) {
        updateSession(state)
        when {
            state.playing -> {
                goForeground(state)
                acquireWakeLock()
            }
            state.set != null && !state.finished -> {
                // Paused: leave a notification to resume from, and let the process rest.
                releaseWakeLock()
                if (foreground) stopForeground(STOP_FOREGROUND_DETACH)
                foreground = false
                notifySafely(notification(state))
                stopSelf()
            }
            else -> {
                releaseWakeLock()
                if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
                stopSelf()
            }
        }
    }

    private fun goForeground(state: DrillPlayer.State) {
        val n = notification(state)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            foreground = true
        }
    }

    private fun notifySafely(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    private fun updateSession(state: DrillPlayer.State) {
        val s = session ?: return
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        val playbackState = when {
            state.playing -> PlaybackState.STATE_PLAYING
            state.set == null || state.finished -> PlaybackState.STATE_STOPPED
            else -> PlaybackState.STATE_PAUSED
        }
        val position = state.plan?.let { it.totalMs - state.remainingMs } ?: 0
        s.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(playbackState, position, 1f).build())
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.set?.summary?.title.orEmpty())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.item?.promptEn.orEmpty())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, state.plan?.totalMs ?: 0)
                .build(),
        )
    }

    private fun notification(state: DrillPlayer.State): Notification {
        val total = state.set?.items?.size ?: 0
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(state.set?.summary?.title ?: getString(R.string.drillset_title))
            .setContentText(
                if (total == 0) "" else getString(R.string.drillset_item_of, (state.itemIndex + 1).coerceAtMost(total), total) +
                    (state.item?.promptEn?.let { " · $it" } ?: ""),
            )
            .setContentIntent(content)
            .setOngoing(state.playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous, R.string.drillset_previous, ACTION_PREVIOUS, 1))
            .addAction(
                if (state.playing) action(android.R.drawable.ic_media_pause, R.string.drillset_pause_button, ACTION_PAUSE, 2)
                else action(android.R.drawable.ic_media_play, R.string.drillset_play, ACTION_PLAY, 2),
            )
            .addAction(action(android.R.drawable.ic_media_next, R.string.drillset_next, ACTION_NEXT, 3))
            .setDeleteIntent(pending(ACTION_STOP, 4))
        session?.let { builder.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1, 2)) }
        return builder.build()
    }

    private fun action(icon: Int, label: Int, action: String, code: Int): Notification.Action =
        Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), pending(action, code)).build()

    private fun pending(action: String, code: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, code, Intent(this, DrillPlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tsumugi:drills").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MAX_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) runCatching { it.release() } }
        wakeLock = null
    }

    companion object {
        private const val CHANNEL = "drills"
        private const val NOTIFICATION_ID = 4712
        /** Safety bound: a forgotten session can't hold the CPU awake for more than two hours. */
        private const val WAKE_LOCK_MAX_MS = 2 * 60 * 60 * 1000L
        const val ACTION_PLAY = "app.tsumugi.drills.PLAY"
        const val ACTION_PAUSE = "app.tsumugi.drills.PAUSE"
        const val ACTION_NEXT = "app.tsumugi.drills.NEXT"
        const val ACTION_PREVIOUS = "app.tsumugi.drills.PREVIOUS"
        const val ACTION_STOP = "app.tsumugi.drills.STOP"

        /** Starts (or refreshes) the service; called by [DrillPlayer.play] from the foreground UI. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DrillPlaybackService::class.java)) }
        }
    }
}
