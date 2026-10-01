package app.mokuhyo.lang

/**
 * Text-to-speech for every feature (BRIEF §3.3): the bundled voice service first, then the OS voice, then
 * pre-rendered clips. Returns WAV bytes, or null when no voice can speak [language] on this computer (the caller
 * degrades to text and says so).
 */
interface SpeechOutput {
    suspend fun synthesize(text: String, language: String, voice: VoiceSpec? = null, speed: Double = 1.0): SpokenAudio?

    /** Voices that can speak [language] on this computer right now, best first. */
    fun voicesFor(language: String): List<VoiceSpec>
}

/** Synthesized audio and which voice produced it (shown in Settings and logs). */
class SpokenAudio(val wav: ByteArray, val voice: VoiceSpec)
