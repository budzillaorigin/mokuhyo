package app.mokuhyo.desktop

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.tts.VoiceService

/** The app's speech output: the bundled voice service (Piper), falling back to the OS voice inside it. */
object SpeechProvider {
    @Volatile private var override: SpeechOutput? = null
    private val service: SpeechOutput by lazy { runCatching { VoiceService.create() }.getOrElse { OsSpeech } }

    fun get(@Suppress("UNUSED_PARAMETER") app: AppGraph): SpeechOutput = override ?: service

    fun set(output: SpeechOutput) {
        override = output
    }

    fun close() {
        (service as? AutoCloseable)?.close()
    }
}
