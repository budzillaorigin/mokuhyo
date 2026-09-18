package app.tsumugi.android.platform

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Japanese text-to-speech with the platform engine (on-device, no service). [speaking] exposes the character
 * range currently being spoken so the reader can highlight it.
 */
class Speech(context: Context) {
    private val _speaking = MutableStateFlow<IntRange?>(null)
    val speaking: StateFlow<IntRange?> = _speaking.asStateFlow()

    private var ready = false
    private var offset = 0
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS && tts.setLanguage(Locale.JAPAN) >= TextToSpeech.LANG_AVAILABLE
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { _speaking.value = null }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _speaking.value = null }
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                _speaking.value = (offset + start) until (offset + end)
            }
        })
    }

    /** Whether a Japanese voice is installed (Settings → Text-to-speech on the device). */
    val available: Boolean get() = ready

    /** Speaks [text]; [startOffset] is its position in a larger document, for highlighting. */
    fun speak(text: String, rate: Float = 1f, startOffset: Int = 0) {
        if (!ready) return
        offset = startOffset
        tts.setSpeechRate(rate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tsumugi")
    }

    fun stop() {
        tts.stop()
        _speaking.value = null
    }

    fun shutdown() = tts.shutdown()
}
