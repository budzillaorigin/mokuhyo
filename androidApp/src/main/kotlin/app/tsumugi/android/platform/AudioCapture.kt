package app.tsumugi.android.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import app.tsumugi.ai.TranscriptSegment
import app.tsumugi.api.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000

/** 16 kHz mono PCM16 audio. It stays in memory on this device; nothing here uploads it. */
class Recording(val pcm: ShortArray) {
    /** The same audio as floats in [-1, 1] (what the pronunciation analyzer and whisper.cpp take). */
    val samples: FloatArray by lazy { FloatArray(pcm.size) { pcm[it] / 32768f } }
    val durationMs: Long get() = pcm.size * 1000L / SAMPLE_RATE
    val isEmpty: Boolean get() = pcm.isEmpty()

    companion object {
        val EMPTY = Recording(ShortArray(0))
    }
}

/** Microphone capture with [AudioRecord] at 16 kHz mono PCM16, on its own thread. Needs RECORD_AUDIO. */
class AudioRecorder(private val maxSeconds: Int = 60) {
    private val chunks = ArrayList<ShortArray>()
    @Volatile private var running = false
    private var thread: Thread? = null

    private val _level = MutableStateFlow(0f)
    /** Input level 0..1 for a simple meter. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Starts recording; returns an error message, or null when the microphone is running. */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO through SpeechInput / rememberMicPermission
    fun start(): String? {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return "This device can't record 16 kHz audio."
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SAMPLE_RATE / 2),
            )
        } catch (e: Exception) {
            return "The microphone is unavailable (${e.message})."
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return "The microphone is unavailable (another app may be using it)."
        }
        synchronized(chunks) { chunks.clear() }
        running = true
        record.startRecording()
        thread = Thread({
            val buffer = ShortArray(SAMPLE_RATE / 10)
            var total = 0
            try {
                while (running && total < maxSeconds * SAMPLE_RATE) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) continue
                    synchronized(chunks) { chunks += buffer.copyOf(n) }
                    total += n
                    var sum = 0.0
                    for (i in 0 until n) sum += buffer[i] * buffer[i].toDouble()
                    _level.value = (sqrt(sum / n) / 8000.0).toFloat().coerceIn(0f, 1f)
                }
            } finally {
                runCatching { record.stop() }
                record.release()
                _level.value = 0f
            }
        }, "tsumugi-mic").apply { start() }
        return null
    }

    fun stop(): Recording {
        running = false
        thread?.join(2_000)
        thread = null
        val all = synchronized(chunks) { chunks.toList().also { chunks.clear() } }
        val out = ShortArray(all.sumOf { it.size })
        var at = 0
        for (c in all) {
            c.copyInto(out, at)
            at += c.size
        }
        return Recording(out)
    }
}

/** What the learner said: the transcript (possibly empty), which engine heard it, and the audio. */
class SpeechResult(
    val transcript: String,
    val segments: List<TranscriptSegment>,
    val engine: String,
    val audio: Recording,
    val error: String? = null,
) {
    /** Recognizer timestamps in the shape the pronunciation analyzer takes. */
    val analyzerSegments: List<Pair<LongRange, String>>? get() =
        segments.takeIf { it.isNotEmpty() }?.map { (it.startMs until it.endMs) to it.text }
}

/**
 * Push-to-talk speech input (BRIEF §5.10). Records with [AudioRecorder] and transcribes with:
 * - the configured engine from `graph.ai.recognizer()` (on-device Whisper, or the learner's own Whisper server), or
 * - the Android recognizer in Japanese, preferring offline recognition. On Android 13+ it is fed the recording, so
 *   the audio is kept for pronunciation scoring; older versions let it listen live, and no audio is kept.
 *
 * Recordings stay on the device. Only "Whisper server" mode sends audio, and only to the learner's configured server.
 */
class SpeechInput(context: Context, private val graph: AppGraph) {
    private val appContext = context.applicationContext

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object Transcribing : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var recorder: AudioRecorder? = null
    private var live: LiveRecognition? = null

    /** Input level 0..1 while recording (0 in live mode, where the system owns the microphone). */
    val level: StateFlow<Float>? get() = recorder?.level

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts listening; returns an error message, or null. */
    suspend fun start(): String? {
        if (_state.value != State.Idle) return null
        if (!hasPermission()) return "Tsumugi needs microphone access to hear you. You can also type."
        val useLive = graph.ai.recognizer() == null && Build.VERSION.SDK_INT < 33
        if (useLive) {
            val l = withContext(Dispatchers.Main) { LiveRecognition(appContext).also { it.start() } }
            l.error?.let { return it }
            live = l
        } else {
            val r = AudioRecorder()
            r.start()?.let { return it }
            recorder = r
        }
        _state.value = State.Listening
        return null
    }

    /** Stops listening and transcribes. */
    suspend fun stop(): SpeechResult {
        val l = live
        val r = recorder
        live = null
        recorder = null
        if (l == null && r == null) return SpeechResult("", emptyList(), "", Recording.EMPTY, "Not listening.")
        _state.value = State.Transcribing
        try {
            if (l != null) {
                val (text, error) = withContext(Dispatchers.Main) { l.finish() }
                return SpeechResult(text, emptyList(), "system recognizer", Recording.EMPTY, error)
            }
            val audio = withContext(Dispatchers.IO) { r!!.stop() }
            if (audio.durationMs < 250) return SpeechResult("", emptyList(), "", audio, "That was too short. Hold the button while you speak.")
            val recognizer = graph.ai.recognizer()
            if (recognizer != null) {
                return try {
                    val t = recognizer.transcribe(audio.pcm, "ja")
                    SpeechResult(t.text, t.segments, t.engine, audio)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    SpeechResult("", emptyList(), "", audio, "Speech recognition failed: ${e.message}")
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                val (text, error) = systemTranscribe(audio.pcm)
                return SpeechResult(text, emptyList(), "system recognizer", audio, error)
            }
            return SpeechResult("", emptyList(), "", audio, "No speech recognizer is available.")
        } finally {
            _state.value = State.Idle
        }
    }

    fun cancel() {
        live?.let { l -> l.cancel() }
        recorder?.stop()
        live = null
        recorder = null
        _state.value = State.Idle
    }

    /** Feeds a finished recording to the Android recognizer through EXTRA_AUDIO_SOURCE (Android 13+). */
    @RequiresApi(33)
    private suspend fun systemTranscribe(pcm: ShortArray): Pair<String, String?> = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            return@withContext "" to "No speech recognizer is installed. Choose on-device Whisper in Settings → AI & speech."
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(appContext)
        val writer = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    val bytes = ByteArray(pcm.size * 2 + SAMPLE_RATE) // + 0.5 s of silence so end-of-speech is detected
                    pcm.forEachIndexed { i, s ->
                        bytes[2 * i] = (s.toInt() and 0xFF).toByte()
                        bytes[2 * i + 1] = (s.toInt() shr 8).toByte()
                    }
                    out.write(bytes)
                }
            } catch (_: IOException) {
                // The recognizer closed its end early; its result or error still arrives through the listener.
            }
        }, "tsumugi-stt-feed")
        try {
            withTimeoutOrNull(30_000) {
                suspendCancellableCoroutine<Pair<String, String?>> { cont ->
                    recognizer.setRecognitionListener(listener { text, error -> if (cont.isActive) cont.resume(text to error) })
                    val intent = recognizerIntent().apply {
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                    }
                    recognizer.startListening(intent)
                    writer.start()
                    cont.invokeOnCancellation { recognizer.cancel() }
                }
            } ?: ("" to "The speech recognizer didn't answer.")
        } finally {
            recognizer.destroy()
            runCatching { pipe[0].close() }
        }
    }
}

/** The Android recognizer listening to the microphone itself (Android 12 and older). Main thread only. */
private class LiveRecognition(context: Context) {
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
    private val result = CompletableDeferred<Pair<String, String?>>()
    var error: String? = if (recognizer == null) "No speech recognizer is installed. Choose on-device Whisper in Settings → AI & speech." else null
        private set

    fun start() {
        val r = recognizer ?: return
        r.setRecognitionListener(listener { text, error -> result.complete(text to error) })
        r.startListening(recognizerIntent())
    }

    suspend fun finish(): Pair<String, String?> {
        recognizer?.stopListening()
        return try {
            withTimeoutOrNull(15_000) { result.await() } ?: ("" to "The speech recognizer didn't answer.")
        } finally {
            recognizer?.destroy()
        }
    }

    fun cancel() {
        recognizer?.cancel()
        recognizer?.destroy()
    }
}

private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
}

private fun listener(onDone: (String, String?) -> Unit) = object : RecognitionListener {
    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
        onDone(text, if (text.isBlank()) "Nothing was recognized. Try again, a little closer." else null)
    }

    override fun onError(error: Int) = onDone("", recognizerError(error))
    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}

private fun recognizerError(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing was recognized. Try again, a little closer."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
        "The system recognizer needs its offline Japanese pack (Android settings → speech recognition), or choose on-device Whisper in Settings → AI & speech."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy; try again."
    else -> if (Build.VERSION.SDK_INT >= 31 && (code == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || code == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
        "The system recognizer has no Japanese. Choose on-device Whisper in Settings → AI & speech."
    } else {
        "Speech recognition failed (code $code)."
    }
}

/** RECORD_AUDIO state plus a launcher for the system permission dialog. */
class MicPermission(val granted: Boolean, val request: () -> Unit)

@Composable
fun rememberMicPermission(): MicPermission {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    return MicPermission(granted) { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}
