package app.mokuhyo.desktop

import app.mokuhyo.lang.SpeechOutput

/** The app's speech output: the OS voice for now; the bundled voice service takes over once it is installed. */
object SpeechProvider {
    @Volatile private var override: SpeechOutput? = null

    fun get(@Suppress("UNUSED_PARAMETER") app: AppGraph): SpeechOutput = override ?: OsSpeech

    fun set(output: SpeechOutput) {
        override = output
    }
}
