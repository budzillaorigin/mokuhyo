package app.mokuhyo.desktop

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.platform.Os
import app.mokuhyo.tts.OsVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The OS voice as a [SpeechOutput] (used until the bundled voice service is wired in). */
object OsSpeech : SpeechOutput {
    override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double): SpokenAudio? =
        withContext(Dispatchers.IO) { OsVoice.synthesize(text, language, speed)?.let { SpokenAudio(it, osVoice(language)) } }

    override fun voicesFor(language: String): List<VoiceSpec> = if (OsVoice.available) listOf(osVoice(language)) else emptyList()

    private fun osVoice(language: String) = VoiceSpec("os-${Os.current.id}-$language", language, "female", "os", "OS voice")
}
