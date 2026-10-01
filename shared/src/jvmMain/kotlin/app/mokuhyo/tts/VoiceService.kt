package app.mokuhyo.tts

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import kotlinx.coroutines.CancellationException

/** One source of voices: the bundled Piper service, the OS voice, … ([VoiceService] tries them in order). */
interface SpeechEngine {
    /** Matches [VoiceSpec.engine] of the voices this engine lists. */
    val engine: String

    /** Voices this engine can use for [language] on this computer right now, best first. */
    fun voicesFor(language: String): List<VoiceSpec>

    /**
     * WAV bytes of [text] spoken by [voice] at [speed] (1.0 = the voice's normal pace), or null when this engine
     * can't produce it (the service then tries the next voice). Runs off the caller's thread; cancellable.
     */
    suspend fun synthesize(text: String, voice: VoiceSpec, speed: Double): ByteArray?
}

/**
 * The app's text-to-speech (BRIEF §3.3): the bundled Piper voices first (out-of-process, see [PiperEngine]), then
 * the operating system's voice ([OsSpeechEngine]: macOS `say`, Windows SAPI), else null — the caller then shows the
 * text and says there is no voice for this language on this computer.
 *
 * Within an engine, voices of the requested gender come first; an explicitly requested [VoiceSpec] is tried before
 * anything else. A voice that fails (crashed process, timeout, missing file) falls through to the next one, so a
 * broken bundled voice degrades to the OS voice instead of to silence.
 */
class VoiceService(private val engines: List<SpeechEngine>) : SpeechOutput, AutoCloseable {

    override fun voicesFor(language: String): List<VoiceSpec> =
        engines.flatMap { e -> runCatching { e.voicesFor(language) }.getOrDefault(emptyList()) }

    override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double): SpokenAudio? {
        if (text.isBlank()) return null
        val pace = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        for (candidate in candidates(language, voice)) {
            val engine = engines.firstOrNull { it.engine == candidate.engine } ?: continue
            val wav = try {
                engine.synthesize(text, candidate, pace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                System.err.println("tts: ${candidate.engine}/${candidate.id} failed: ${e.message}")
                null
            }
            if (wav != null && Wav.isValid(wav)) return SpokenAudio(wav, candidate)
        }
        return null
    }

    /**
     * Every voice to try, in order: the requested one (if an engine lists it for [language]), then per engine the
     * requested gender first. A requested voice that isn't installed only contributes its gender.
     */
    internal fun candidates(language: String, requested: VoiceSpec?): List<VoiceSpec> {
        val gender = requested?.gender
        val listed = engines.flatMap { e ->
            runCatching { e.voicesFor(language) }.getOrDefault(emptyList())
                .sortedBy { if (gender != null && it.gender == gender) 0 else 1 }
        }
        val first = requested?.let { r ->
            listed.firstOrNull { it.engine == r.engine && it.id == r.id && (r.speaker == null || it.speaker == r.speaker) }
        }
        return (listOfNotNull(first) + listed).distinctBy { Triple(it.engine, it.id, it.speaker) }
    }

    override fun close() {
        engines.forEach { (it as? AutoCloseable)?.runCatching { close() } }
    }

    companion object {
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 2.0

        /** The default service: bundled Piper voices found by [VoiceCatalog.discover], then the OS voice. */
        fun create(catalog: VoiceCatalog = VoiceCatalog.discover()): VoiceService =
            VoiceService(listOf(PiperEngine(catalog), OsSpeechEngine()))

        internal fun sameLanguage(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)
    }
}
