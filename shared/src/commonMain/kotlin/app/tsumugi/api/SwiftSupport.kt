package app.tsumugi.api

import app.tsumugi.ai.AiResult
import app.tsumugi.ai.ModelInfo
import app.tsumugi.ai.ModelKind
import app.tsumugi.ai.prompts.JlptExplainItem
import app.tsumugi.ai.prompts.OpiPhase
import app.tsumugi.ai.prompts.Speaker
import app.tsumugi.exam.ExamKind
import app.tsumugi.exam.ExamService
import app.tsumugi.exam.ExamSession
import app.tsumugi.exam.dlpt.IlrLevel
import app.tsumugi.exam.opi.OpiRating
import app.tsumugi.exam.opi.OpiSession
import app.tsumugi.speaking.AiConfig
import app.tsumugi.speaking.AiService
import app.tsumugi.speaking.LlmEngine
import app.tsumugi.speaking.SttEngine
import app.tsumugi.speaking.TtsEngine
import app.tsumugi.speech.PronunciationReport
import app.tsumugi.study.activities.PomodoroSession
import kotlinx.coroutines.CancellationException

/** A picker option: [key] is the enum name, stable across releases. */
data class EngineChoice(val key: String, val label: String, val detail: String)

/** Speech-to-text result, or [error] when the configured engine failed. */
data class SttOutcome(val text: String, val engine: String, val error: String?)

/** `GET /v1/models` result for "Test connection". */
data class ProbeOutcome(val models: List<String>, val error: String?)

/** A labeled AI answer; [engine] is null when nothing was generated and [unavailable] says why. */
data class AiExplanation(val explanation: String, val whyWrong: String, val keyPoint: String, val engine: String?, val unavailable: String?)

data class TranscriptLine(val learner: Boolean, val text: String)

data class ChecklistEntry(val level: String, val statement: String)

/**
 * Small non-generic, non-throwing adapters for the Swift app (Kotlin generics, `Result`, `Duration`, `Pair`
 * and exceptions from suspend functions don't bridge cleanly through Objective-C). No logic lives here.
 */
object SwiftSupport {
    fun llmChoices(): List<EngineChoice> = listOf(
        EngineChoice(LlmEngine.NONE.name, "Off", "Deterministic fallbacks only; no generated text."),
        EngineChoice(LlmEngine.LOCAL.name, "On-device model", "Runs on this device with llama.cpp. Works offline."),
        EngineChoice(LlmEngine.ENDPOINT.name, "My own server", "Any OpenAI-compatible server you run (Ollama, LM Studio, llama-server, vLLM)."),
    )

    fun sttChoices(): List<EngineChoice> = listOf(
        EngineChoice(SttEngine.SYSTEM.name, "System (on-device)", "Apple's on-device recognizer, when it supports Japanese offline."),
        EngineChoice(SttEngine.WHISPER_LOCAL.name, "Whisper on device", "Downloaded Whisper model, runs on this device."),
        EngineChoice(SttEngine.WHISPER_ENDPOINT.name, "Whisper on my server", "A Whisper-compatible /v1/audio/transcriptions server you run."),
    )

    fun ttsChoices(): List<EngineChoice> = listOf(
        EngineChoice(TtsEngine.SYSTEM.name, "System voices", "The device's Japanese voices."),
        EngineChoice(TtsEngine.VOICEVOX.name, "VOICEVOX", "A VOICEVOX engine you run, e.g. on a home server."),
    )

    fun llmKey(config: AiConfig): String = config.llm.name
    fun sttKey(config: AiConfig): String = config.stt.name
    fun ttsKey(config: AiConfig): String = config.tts.name

    fun buildConfig(
        llm: String,
        localModelId: String?,
        endpointUrl: String,
        endpointModel: String,
        stt: String,
        localSttModelId: String?,
        sttEndpointUrl: String,
        tts: String,
        voicevoxUrl: String,
        voicevoxSpeaker: Int,
    ): AiConfig = AiConfig(
        llm = LlmEngine.entries.firstOrNull { it.name == llm } ?: LlmEngine.NONE,
        localModelId = localModelId,
        endpointUrl = endpointUrl,
        endpointModel = endpointModel,
        stt = SttEngine.entries.firstOrNull { it.name == stt } ?: SttEngine.SYSTEM,
        localSttModelId = localSttModelId,
        sttEndpointUrl = sttEndpointUrl,
        tts = TtsEngine.entries.firstOrNull { it.name == tts } ?: TtsEngine.SYSTEM,
        voicevoxUrl = voicevoxUrl,
        voicevoxSpeaker = voicevoxSpeaker,
    )

    fun models(ai: AiService, speech: Boolean): List<ModelInfo> = ai.models?.models(kind(speech)).orEmpty()

    fun recommended(ai: AiService, speech: Boolean): ModelInfo? = ai.models?.recommend(kind(speech), ai.deviceRamGb)

    /** Deletes a downloaded model; returns an error message or null. */
    fun deleteModel(ai: AiService, model: ModelInfo): String? =
        runCatching { ai.models?.delete(model) }.exceptionOrNull()?.let { it.message ?: "couldn't delete the model" }

    suspend fun probe(ai: AiService, url: String, apiKey: String?): ProbeOutcome =
        ai.probeEndpoint(url, apiKey?.takeIf { it.isNotBlank() }).fold(
            onSuccess = { ProbeOutcome(it, null) },
            onFailure = { ProbeOutcome(emptyList(), it.message ?: "connection failed") },
        )

    /**
     * Transcribes 16 kHz mono samples in [-1, 1] with the configured engine. Null means "no engine configured here;
     * use the OS recognizer".
     */
    suspend fun transcribe(ai: AiService, samples: FloatArray): SttOutcome? {
        val recognizer = ai.recognizer() ?: return null
        val pcm = ShortArray(samples.size) { (samples[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }
        return try {
            val t = recognizer.transcribe(pcm, "ja")
            SttOutcome(t.text, t.engine, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SttOutcome("", "", e.message ?: "transcription failed")
        }
    }

    /** VOICEVOX audio written to a WAV file in the app's data folder; null means "use the system voice". */
    suspend fun synthesizeToFile(graph: AppGraph, text: String, speed: Double): String? = try {
        graph.ai.synthesizer()?.synthesize(text, null, speed)?.let { bytes ->
            val dir = graph.platform.dataDir / "tts"
            graph.platform.fileSystem.createDirectories(dir)
            val path = dir / "voicevox.wav"
            graph.platform.fileSystem.write(path) { write(bytes) }
            path.toString()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun analyzePronunciation(graph: AppGraph, sentence: String, transcript: String?, samples: FloatArray): PronunciationReport? = try {
        graph.pronunciation.analyze(sentence, transcript, samples, null)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun explainItem(
        ai: AiService,
        level: String,
        question: String,
        choices: List<String>,
        correctIndex: Int,
        /** -1 when unanswered. */
        chosenIndex: Int,
        stimulus: String?,
    ): AiExplanation {
        val input = JlptExplainItem.Input(level, question, choices, correctIndex, chosenIndex.takeIf { it >= 0 }, stimulus)
        return when (val r = ai.gateway().run(JlptExplainItem(), input)) {
            is AiResult.Ok -> AiExplanation(r.value.explanation, r.value.whyWrong, r.value.keyPoint, r.engine, null)
            is AiResult.Fallback -> AiExplanation(r.value.explanation, r.value.whyWrong, r.value.keyPoint, null, null)
            is AiResult.Unavailable -> AiExplanation("", "", "", null, r.reason)
        }
    }

    suspend fun dlpt(exams: ExamService, listening: Boolean, minutes: Int): ExamSession =
        exams.dlpt(if (listening) ExamKind.DLPT_LISTENING else ExamKind.DLPT_READING, minutes, kotlin.time.Clock.System.now().toEpochMilliseconds())

    suspend fun opi(graph: AppGraph, startLevel: String): OpiSession? = graph.opi(IlrLevel.parse(startLevel) ?: IlrLevel.L1)

    fun opiStartLevels(): List<String> = IlrLevel.lowerRange.map { it.label }

    fun opiTranscript(session: OpiSession): List<TranscriptLine> = session.transcript.map { TranscriptLine(it.first == Speaker.LEARNER, it.second) }

    fun opiPhaseTitle(phase: OpiPhase): String = when (phase) {
        OpiPhase.WARMUP -> "Warm-up"
        OpiPhase.LEVEL_CHECK -> "Level check"
        OpiPhase.PROBE -> "Probe"
        OpiPhase.ROLEPLAY -> "Role-play"
        OpiPhase.WINDDOWN -> "Wind-down"
    }

    fun opiChecklist(session: OpiSession): List<ChecklistEntry> = session.checklist().map { ChecklistEntry(it.first.label, it.second) }

    fun opiSelfRate(session: OpiSession, checked: List<String>): OpiRating = session.selfRate(checked.toSet())

    fun ilrLabel(rating: OpiRating): String? = rating.ilr?.label

    suspend fun saveOpi(exams: ExamService, session: OpiSession, rating: OpiRating): String =
        exams.saveOpi(session.startedAt, session.transcript.map { it.first.name to it.second }, rating)

    /** Transcript of a saved OPI attempt. */
    suspend fun opiAttemptTranscript(exams: ExamService, attemptId: String): List<TranscriptLine> =
        exams.opiTranscript(attemptId).map { TranscriptLine(it.first == Speaker.LEARNER.name, it.second) }

    fun pomodoroRemainingSeconds(session: PomodoroSession): Long = session.remaining.inWholeSeconds

    fun pomodoroBreakRemainingSeconds(session: PomodoroSession): Long = session.breakRemaining.inWholeSeconds

    private fun kind(speech: Boolean) = if (speech) ModelKind.STT else ModelKind.LLM
}
