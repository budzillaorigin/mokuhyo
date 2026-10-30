package app.mokuhyo.tts

import app.mokuhyo.lang.SpeechOutput
import app.mokuhyo.lang.SpokenAudio
import app.mokuhyo.lang.VoiceSpec
import app.mokuhyo.speech.SpeechBudget
import app.mokuhyo.speech.currentSpeechBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

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

    /** What this engine can do (QC 2026-10-30 V4). Piper and the OS voice return WAVs only. */
    val capabilities: EngineCapabilities get() = EngineCapabilities.WAV_ONLY

    /** [request] as PCM while it is generated, for a [EngineCapabilities.pcmStream] engine; null otherwise. Cancellable. */
    fun stream(request: SpeechRequest): kotlinx.coroutines.flow.Flow<PcmChunk>? = null
}

/**
 * The app's text-to-speech (BRIEF §3.3): the bundled Piper voices first (out-of-process, see [PiperEngine]), then
 * the operating system's voice ([OsSpeechEngine]: macOS `say`, Windows SAPI), else null — the caller then shows the
 * text and says there is no voice for this language on this computer.
 *
 * Within an engine, voices of the requested gender come first; an explicitly requested [VoiceSpec] is tried before
 * anything else. A voice that fails (crashed process, timeout, missing file) falls through to the next one, so a
 * broken bundled voice degrades to the OS voice instead of to silence. When the caller carries a [SpeechBudget], every
 * attempt runs within what remains of it and no new attempt starts once it is spent (QC 2026-10-30 V6).
 */
class VoiceService(private val engines: List<SpeechEngine>) : SpeechOutput, AutoCloseable {

    override fun voicesFor(language: String): List<VoiceSpec> =
        engines.flatMap { e -> runCatching { e.voicesFor(language) }.getOrDefault(emptyList()) }

    override suspend fun synthesize(text: String, language: String, voice: VoiceSpec?, speed: Double): SpokenAudio? {
        if (text.isBlank()) return null
        val pace = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val budget = currentSpeechBudget()
        for (candidate in candidates(language, voice)) {
            val engine = engines.firstOrNull { it.engine == candidate.engine } ?: continue
            val remaining = budget?.remainingMs() ?: Long.MAX_VALUE
            if (remaining <= 0) {
                System.err.println("tts: speech budget spent before ${candidate.engine}/${candidate.id} could try")
                return null
            }
            val wav = try {
                if (remaining == Long.MAX_VALUE) engine.synthesize(text, candidate, pace)
                else when (val r = withTimeoutOrNull(remaining) { Result.success(engine.synthesize(text, candidate, pace)) }) {
                    null -> null.also { System.err.println("tts: ${candidate.engine}/${candidate.id} ran past the speech budget") }
                    else -> r.getOrNull()
                }
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

    /**
     * The engine that streams PCM for [language] in [voice] (or the first such voice), with that voice; null when none
     * does, which is every shipped engine today: callers then use [synthesize] sentence by sentence (V4).
     */
    fun pcmStreamFor(language: String, voice: VoiceSpec?): Pair<SpeechEngine, VoiceSpec>? =
        candidates(language, voice).firstNotNullOfOrNull { v ->
            engines.firstOrNull { it.engine == v.engine && it.capabilities.pcmStream }?.let { it to v }
        }

    override fun close() {
        engines.forEach { (it as? AutoCloseable)?.runCatching { close() } }
    }

    companion object {
        const val MIN_SPEED = 0.5
        const val MAX_SPEED = 2.0

        /** The default service: bundled Piper voices found by [VoiceCatalog.discover], then the OS voice. */
        fun create(catalog: VoiceCatalog = VoiceCatalog.discover(), ttsThreads: () -> Int? = { null }): VoiceService =
            VoiceService(listOf(PiperEngine(catalog, threads = ttsThreads), OsSpeechEngine()))

        internal fun sameLanguage(a: String, b: String): Boolean = a.equals(b, ignoreCase = true)
    }
}
