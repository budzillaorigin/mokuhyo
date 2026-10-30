package app.mokuhyo.desktop.ui.speaking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.mokuhyo.desktop.AppGraph
import app.mokuhyo.desktop.ui.Fonts
import app.mokuhyo.lang.LanguageModule
import app.mokuhyo.platform.Os
import app.mokuhyo.settings.Settings
import app.mokuhyo.speech.AudioIO
import app.mokuhyo.speech.CapturePatience
import app.mokuhyo.speech.TimelineTrace
import app.mokuhyo.speech.TurnStage
import app.mokuhyo.speech.TurnTimeline
import app.mokuhyo.speech.Vad
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Voice input for every speaking mode (BRIEF_PHASE8 N-00b): record at the device's own rate, stop by itself after the
 * silence that ends an answer (or on Stop), say plainly when no audio arrives, and — in practice, unless turned off —
 * show the transcript to send, edit or retake before it goes to the partner. Interview tests pass [confirm] = false.
 *
 * Conversation mode (QC 2026-10-30 V7) is opt-in: [patience] sets the silence that ends a turn (null keeps the default
 * [Vad], exactly as before), [autoArm] starts recording again by itself once the partner has finished and capture is
 * [enabled], and the stop button reads "I'm done". The microphone never records while the partner speaks (no barge-in).
 */
@Composable
fun VoiceCapture(
    app: AppGraph,
    module: LanguageModule,
    confirm: Boolean,
    enabled: Boolean,
    onStatus: (String) -> Unit,
    onBusy: (Boolean) -> Unit,
    /** Words the learner is likely to say (the question, the topic, key terms): Whisper's initial prompt (N-00b). */
    prompt: String = "",
    visible: Boolean = true,
    /** The session's timeline (BRIEF_INTERVIEW_SPEED N1): capture, endpoint and ASR stages go to its current turn. */
    timeline: TurnTimeline? = null,
    patience: CapturePatience? = null,
    autoArm: Boolean = false,
    onSend: (String, ShortArray) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var rec by remember { mutableStateOf<AudioIO.Recording?>(null) }
    var level by remember { mutableStateOf(0.0) }
    var pending by remember { mutableStateOf<Pair<String, ShortArray>?>(null) }
    var edit by remember { mutableStateOf("") }
    var transcription by remember { mutableStateOf<Job?>(null) }
    var audio by remember { mutableStateOf<ShortArray?>(null) }
    var captureWork by remember { mutableStateOf<Job?>(null) }
    var vad by remember { mutableStateOf<Vad?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            captureWork?.cancel()
            transcription?.cancel()
            val abandoned = rec
            rec = null
            // The composition's scope is being cancelled. The application owns device cleanup, off the UI thread.
            if (abandoned != null) app.scope.launch(Dispatchers.IO) { runCatching { abandoned.stop() } }
        }
    }

    fun transcribe(pcm: ShortArray) {
        audio = pcm
        onBusy(true)
        onStatus("Transcribing…")
        timeline?.note { answerMs = pcm.size / 16L }
        timeline?.mark(TurnStage.ASR_QUEUED)
        transcription = scope.launch(timeline?.let { TimelineTrace(it) } ?: EmptyCoroutineContext) {
            try {
                val text = withContext(Dispatchers.IO) { app.recognizer()?.transcribe(pcm, module.sttLanguage, prompt)?.text.orEmpty().trim() }
                when {
                    text.isBlank() -> onStatus("I didn't catch anything. Try again, or type your answer.")
                    confirm -> { pending = text to pcm; edit = text; onStatus("Check what I heard, then send it — or edit it, or record again.") }
                    else -> { audio = null; onStatus(""); onSend(text, pcm) }
                }
            } catch (e: CancellationException) {
                timeline?.mark(TurnStage.CANCEL_EFFECTIVE)
                onStatus("Transcription stopped. You can retry the saved audio or record again.")
                throw e
            } catch (e: Exception) {
                onStatus("Couldn't transcribe: ${e.message}. Retry the saved audio or type your answer.")
            } finally {
                transcription = null
                onBusy(false)
            }
        }
    }

    fun finish(noSpeech: Boolean = false, auto: Boolean = false) {
        val r = rec ?: return
        rec = null
        // The learner's last speech: back-dated by the silence the VAD has heard since (N1 headline start).
        vad?.takeIf { it.heardSpeech && !auto }?.let { timeline?.markBefore(TurnStage.LAST_SPEECH, it.trailingSilenceMs.toLong()) }
        level = 0.0
        onBusy(true)
        onStatus("Finishing recording…")
        captureWork = scope.launch {
            try {
                val pcm = withContext(NonCancellable + Dispatchers.IO) { r.stop() }
                timeline?.mark(TurnStage.CAPTURE_STOP)
                audio = pcm
                currentCoroutineContext().ensureActive()
                if (noSpeech) onStatus(noAudioHint() + " You can retry transcription of this audio.") else transcribe(pcm)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStatus("Couldn't finish the recording: ${e.message}. Try recording again.")
            } finally {
                captureWork = null
                onBusy(transcription != null)
            }
        }
    }

    fun start() {
        if (captureWork != null || transcription != null || rec != null) return
        pending = null
        audio = null
        onBusy(true)
        onStatus("Opening microphone…")
        captureWork = scope.launch {
            var opened: AudioIO.Recording? = null
            try {
                // If cancellation arrives during a blocking driver open, retain the returned handle for cleanup.
                withContext(NonCancellable + Dispatchers.IO) {
                    val detector = (patience?.vad() ?: Vad()).also { vad = it }
                    opened = AudioIO.record(app.settings.get(Settings.Key.INPUT_DEVICE), onLevel = { level = it }, vad = detector, onEvent = { e ->
                        // Marked on the capture thread, when the VAD decided, not when the UI got round to it.
                        if (e == Vad.Event.AUTO_STOP) timeline?.let { t ->
                            if (detector.heardSpeech) t.markBefore(TurnStage.LAST_SPEECH, detector.trailingSilenceMs.toLong())
                            t.mark(TurnStage.VAD_ENDPOINT)
                        }
                        scope.launch {
                            when (e) {
                                Vad.Event.AUTO_STOP -> finish(auto = true)
                                Vad.Event.NO_AUDIO -> finish(noSpeech = true)
                                Vad.Event.SPEECH_START -> onStatus("Listening… it stops by itself when you finish.")
                            }
                        }
                    })
                }
                currentCoroutineContext().ensureActive()
                rec = opened
                opened = null // ownership transfers to the composition only after cancellation is checked
                onStatus("Recording… speak now. Press Stop when finished; each recording is limited to 2 minutes.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onStatus("Couldn't open the microphone: ${e.message}")
            } finally {
                opened?.let { withContext(NonCancellable + Dispatchers.IO) { runCatching { it.stop() } } }
                captureWork = null
                onBusy(rec != null || transcription != null)
            }
        }
    }

    // Conversation mode: re-arm once the partner has finished. Not after a failed or unclear take (audio is kept for a
    // retry then), and never while anything else holds the microphone or the recognizer.
    LaunchedEffect(autoArm, enabled, visible) {
        if (autoArm && enabled && visible && rec == null && pending == null && captureWork == null && transcription == null && audio == null) start()
    }

    // Keep ownership and cleanup composed even while the parent hides the controls.
    if (!visible) return
    val p = pending
    if (p != null) {
        OutlinedTextField(edit, { edit = it }, Modifier.fillMaxWidth(), label = { Text("What I heard") },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = Fonts.forLanguage(module.code)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = edit.isNotBlank(), onClick = { pending = null; audio = null; onStatus(""); onSend(edit.trim(), p.second) }) { Text("Send") }
            OutlinedButton(onClick = { pending = null; start() }) { Text("Record again") }
        }
        return
    }
    Column {
        if (captureWork != null && rec == null) {
            Text("Microphone operation in progress…")
        }
        if (transcription != null) {
            OutlinedButton(onClick = { timeline?.mark(TurnStage.CANCEL_REQUESTED); transcription?.cancel() }) { Text("Cancel transcription") }
        } else if (audio != null && captureWork == null) {
            OutlinedButton(onClick = { audio?.let(::transcribe) }) { Text("Retry transcription") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = (enabled && transcription == null && captureWork == null) || rec != null, onClick = { if (rec == null) start() else finish() }) {
                Text(if (rec != null) (if (autoArm) "■ I'm done" else "■ Stop") else "● Record")
            }
        }
        if (rec != null) LinearProgressIndicator(progress = { (level * 4).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
    }
}

/** "No audio detected" with where to look on this OS (macOS microphone permission, Windows privacy setting). */
fun noAudioHint(): String = "No audio detected — check your microphone. " + when (Os.current) {
    Os.MACOS -> "On a Mac: System Settings → Privacy & Security → Microphone → allow Mokuhyo, and pick the right input in Settings → Speech & audio."
    Os.WINDOWS -> "On Windows: Settings → Privacy & security → Microphone → let desktop apps use the microphone, and pick the right input in Settings → Speech & audio."
    Os.LINUX -> "Pick the right input in Settings → Speech & audio and check it isn't muted."
}
