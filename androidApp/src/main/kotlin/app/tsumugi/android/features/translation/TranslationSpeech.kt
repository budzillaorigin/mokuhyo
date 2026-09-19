package app.tsumugi.android.features.translation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import app.tsumugi.android.platform.AudioRecorder
import app.tsumugi.api.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Speech capture for sight translation (§6.12, D-295). Unlike the push-to-talk [app.tsumugi.android.platform.SpeechInput]
 * (always Japanese, short turns), a sight translation is up to five minutes long and J→E is spoken in English, so:
 * - with a configured recognizer (on-device Whisper or the learner's server) it records the whole take and transcribes
 *   it once in the target language;
 * - otherwise the Android recognizer listens live in the target language and restarts after each pause, joining the
 *   pieces (continuous dictation). Nothing is uploaded except to a Whisper server the learner configured.
 */
internal class SightSpeech(context: Context, private val graph: AppGraph, private val languageTag: String) {
    private val appContext = context.applicationContext
    private var recorder: AudioRecorder? = null
    private var live: SpeechRecognizer? = null
    private val pieces = ArrayList<String>()
    private var liveError: String? = null
    @Volatile private var listening = false

    /** Error kinds the screen maps to its own strings. */
    sealed interface Outcome {
        data class Text(val text: String) : Outcome
        data object NoRecognizer : Outcome
        data object Nothing : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** Starts listening; null when running, or the outcome that stopped it. */
    suspend fun start(maxSeconds: Int): Outcome? {
        pieces.clear()
        liveError = null
        if (graph.ai.recognizer() != null) {
            val r = AudioRecorder(maxSeconds)
            r.start()?.let { return Outcome.Failed(it) }
            recorder = r
            listening = true
            return null
        }
        return withContext(Dispatchers.Main) {
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return@withContext Outcome.NoRecognizer
            listening = true
            val sr = SpeechRecognizer.createSpeechRecognizer(appContext)
            live = sr
            sr.setRecognitionListener(listener(sr))
            sr.startListening(intent())
            null
        }
    }

    /** Stops and returns the transcript. */
    suspend fun stop(): Outcome {
        listening = false
        val r = recorder
        recorder = null
        if (r != null) {
            val audio = withContext(Dispatchers.IO) { r.stop() }
            if (audio.durationMs < 300) return Outcome.Nothing
            val recognizer = graph.ai.recognizer() ?: return Outcome.NoRecognizer
            return try {
                val t = recognizer.transcribe(audio.pcm, if (languageTag.startsWith("en")) "en" else "ja")
                if (t.text.isBlank()) Outcome.Nothing else Outcome.Text(t.text.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: e::class.simpleName.orEmpty())
            }
        }
        withContext(Dispatchers.Main) {
            live?.stopListening()
            // Give the recognizer a moment to deliver the last piece.
            kotlinx.coroutines.delay(800)
            live?.destroy()
            live = null
        }
        val text = pieces.joinToString(if (languageTag.startsWith("en")) " " else "").trim()
        return when {
            text.isNotEmpty() -> Outcome.Text(text)
            liveError != null -> Outcome.Failed(liveError!!)
            else -> Outcome.Nothing
        }
    }

    fun cancel() {
        listening = false
        recorder?.stop()
        recorder = null
        live?.let { runCatching { it.cancel(); it.destroy() } }
        live = null
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun listener(sr: SpeechRecognizer) = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { pieces += it }
            if (listening) runCatching { sr.startListening(intent()) }
        }

        override fun onError(error: Int) {
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (!silence) liveError = "code $error"
            // A pause ends one recognition; keep dictating until the learner stops or time runs out.
            if (listening && (silence || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)) runCatching { sr.startListening(intent()) }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
