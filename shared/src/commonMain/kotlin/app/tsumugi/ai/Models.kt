package app.tsumugi.ai

/**
 * AI runtime abstractions (BRIEF §3.5, §7). Every engine is optional: the local engines run on device via native
 * bridges the apps provide (llama.cpp, whisper.cpp), and the endpoint engines talk to a server the learner runs
 * themselves (Ollama, LM Studio, llama-server, vLLM, VOICEVOX). Nothing here requires a paid or keyed service.
 */
interface LanguageModel {
    val id: String
    val isLocal: Boolean
    suspend fun complete(request: CompletionRequest): CompletionResult
}

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val content: String)

data class CompletionRequest(
    val messages: List<ChatMessage>,
    val maxTokens: Int = 512,
    val temperature: Double = 0.4,
    /** Structured output: local models get a GBNF grammar, endpoints get `response_format`. */
    val jsonSchema: JsonSchema? = null,
    val stop: List<String> = emptyList(),
)

/** [engine] is shown in the UI next to AI output, e.g. "on-device Qwen2.5 1.5B" or "endpoint llama3 @ host". */
data class CompletionResult(val text: String, val engine: String, val tokens: Int?)

/** Engine failure (not loaded, network, HTTP error, cancelled). The gateway turns these into fallbacks. */
class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

// --- Speech -------------------------------------------------------------------------------------------------

data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

data class Transcript(val text: String, val segments: List<TranscriptSegment>, val engine: String)

interface SpeechRecognizer {
    /** [pcm16kMono]: 16 kHz, mono, signed 16-bit samples. */
    suspend fun transcribe(pcm16kMono: ShortArray, language: String = "ja"): Transcript
}

interface Synthesizer {
    /** WAV bytes, or null to mean "use the platform TTS instead". */
    suspend fun synthesize(text: String, voice: String? = null, speed: Double = 1.0): ByteArray?
}
