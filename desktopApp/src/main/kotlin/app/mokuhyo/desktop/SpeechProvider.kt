package app.mokuhyo.desktop

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.tts.VoiceService

/** The app's speech output: the bundled voice service (Piper), falling back to the OS voice inside it. */
object SpeechProvider {
    @Volatile private var override: SpeechOutput? = null

    /** The voice's thread cap (N4) from the app's [app.mokuhyo.ai.ThreadBudget], read when a Piper process starts. */
    @Volatile var ttsThreads: () -> Int? = { null }
    private val service: SpeechOutput by lazy { runCatching { VoiceService.create(ttsThreads = { ttsThreads() }) }.getOrElse { OsSpeech } }

    fun get(@Suppress("UNUSED_PARAMETER") app: AppGraph): SpeechOutput = override ?: service

    /** Tests: replaces the voice service; null restores it. */
    fun set(output: SpeechOutput?) {
        override = output
    }

    fun close() {
        (service as? AutoCloseable)?.close()
    }
}
