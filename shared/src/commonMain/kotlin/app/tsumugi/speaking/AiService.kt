package app.tsumugi.speaking

import app.tsumugi.ai.AiGateway
import app.tsumugi.ai.LanguageModel
import app.tsumugi.ai.LocalLlamaModel
import app.tsumugi.ai.LocalLlmBridge
import app.tsumugi.ai.LocalSttBridge
import app.tsumugi.ai.ModelKind
import app.tsumugi.ai.ModelManager
import app.tsumugi.ai.OpenAICompatibleModel
import app.tsumugi.ai.SpeechRecognizer
import app.tsumugi.ai.Synthesizer
import app.tsumugi.ai.ValidationContext
import app.tsumugi.ai.VoicevoxSynthesizer
import app.tsumugi.ai.WhisperEndpointRecognizer
import app.tsumugi.ai.WhisperRecognizer
import app.tsumugi.platform.PlatformServices
import app.tsumugi.settings.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.buffer
import okio.use

/** Which engine answers LLM tasks (BRIEF §7.1). NONE = deterministic fallbacks only. */
enum class LlmEngine { NONE, LOCAL, ENDPOINT }

/** Speech-to-text: the OS recognizer (on-device where the OS supports it), local Whisper, or a Whisper-compatible server. */
enum class SttEngine { SYSTEM, WHISPER_LOCAL, WHISPER_ENDPOINT }

/** Text-to-speech: the OS voices, or a VOICEVOX engine the learner runs (e.g. on a home server). */
enum class TtsEngine { SYSTEM, VOICEVOX }

data class AiConfig(
    val llm: LlmEngine = LlmEngine.NONE,
    val localModelId: String? = null,
    val endpointUrl: String = "",
    val endpointModel: String = "",
    val stt: SttEngine = SttEngine.SYSTEM,
    val localSttModelId: String? = null,
    val sttEndpointUrl: String = "",
    val tts: TtsEngine = TtsEngine.SYSTEM,
    val voicevoxUrl: String = "",
    val voicevoxSpeaker: Int = 3,
) {
    /** A short label for the "AI-generated" badge and settings summary. */
    val llmLabel: String get() = when (llm) {
        LlmEngine.NONE -> "Off"
        LlmEngine.LOCAL -> localModelId ?: "On-device"
        LlmEngine.ENDPOINT -> endpointModel.ifBlank { "Endpoint" }
    }
}

/**
 * Owns the AI engine choice (BRIEF §7): builds the configured [LanguageModel], speech recognizer and synthesizer,
 * and the model downloader. Native bridges are platform code, so the apps set [llmBridge]/[sttBridge] at startup;
 * without them the local options report "not available in this build". Endpoint API keys live only in the
 * keychain/keystore and go only to that endpoint (CLAUDE.md rule 6).
 */
class AiService(
    private val platform: PlatformServices,
    private val settings: SettingsRepository,
    private val isKnownJapanese: (String) -> Boolean = { true },
) {
    var llmBridge: LocalLlmBridge? = null
    var sttBridge: LocalSttBridge? = null

    /** Device RAM in GB, set by the app; drives the model recommendation. */
    var deviceRamGb: Double = 4.0

    val models: ModelManager? by lazy {
        val manifest = platform.openBundled(MANIFEST)?.buffer()?.use { it.readUtf8() }
            ?.let { runCatching { ModelManager.parseManifest(it) }.getOrNull() }
            ?: return@lazy null
        ModelManager(platform.fileSystem, platform.dataDir / "models", platform.httpEngine(), manifest)
    }

    private val lock = Mutex()
    private var cachedModel: Pair<AiConfig, LanguageModel?>? = null
    private var cachedRecognizer: Pair<AiConfig, SpeechRecognizer?>? = null

    @Throws(Exception::class)
    suspend fun config(): AiConfig = AiConfig(
        llm = enumOr(settings.get(LLM), LlmEngine.NONE),
        localModelId = settings.get(LOCAL_MODEL),
        endpointUrl = settings.get(ENDPOINT_URL).orEmpty(),
        endpointModel = settings.get(ENDPOINT_MODEL).orEmpty(),
        stt = enumOr(settings.get(STT), SttEngine.SYSTEM),
        localSttModelId = settings.get(LOCAL_STT_MODEL),
        sttEndpointUrl = settings.get(STT_ENDPOINT_URL).orEmpty(),
        tts = enumOr(settings.get(TTS), TtsEngine.SYSTEM),
        voicevoxUrl = settings.get(VOICEVOX_URL).orEmpty(),
        voicevoxSpeaker = settings.int(VOICEVOX_SPEAKER, 3),
    )

    @Throws(Exception::class)
    suspend fun save(config: AiConfig) {
        settings.put(LLM, config.llm.name)
        config.localModelId?.let { settings.put(LOCAL_MODEL, it) }
        settings.put(ENDPOINT_URL, config.endpointUrl.trim())
        settings.put(ENDPOINT_MODEL, config.endpointModel.trim())
        settings.put(STT, config.stt.name)
        config.localSttModelId?.let { settings.put(LOCAL_STT_MODEL, it) }
        settings.put(STT_ENDPOINT_URL, config.sttEndpointUrl.trim())
        settings.put(TTS, config.tts.name)
        settings.put(VOICEVOX_URL, config.voicevoxUrl.trim())
        settings.put(VOICEVOX_SPEAKER, config.voicevoxSpeaker.toString())
        lock.withLock {
            cachedModel = null
            cachedRecognizer = null
        }
    }

    /** API key for the LLM endpoint; stored in the keychain/keystore, never in the synced settings table. */
    var endpointKey: String?
        get() = platform.secrets.get(ENDPOINT_KEY)
        set(value) {
            if (value.isNullOrBlank()) platform.secrets.remove(ENDPOINT_KEY) else platform.secrets.put(ENDPOINT_KEY, value.trim())
            cachedModel = null
        }

    /** Lists the endpoint's models (GET /v1/models) so settings can offer a picker and prove the URL works. */
    @Throws(Exception::class)
    suspend fun probeEndpoint(url: String, apiKey: String?): Result<List<String>> = runCatching {
        OpenAICompatibleModel(platform.httpEngine(), url, apiKey, "").probe()
    }

    /** The configured model, or null (the gateway then uses fallbacks). */
    @Throws(Exception::class)
    suspend fun model(): LanguageModel? {
        val config = config()
        return lock.withLock {
            cachedModel?.takeIf { it.first == config }?.second ?: build(config).also { cachedModel = config to it }
        }
    }

    @Throws(Exception::class)
    suspend fun gateway(): AiGateway {
        val model = model()
        return AiGateway({ model }, context = ValidationContext(isKnownJapanese))
    }

    /** Why AI features are off, or null when a model is configured. Shown with a link to AI settings. */
    @Throws(Exception::class)
    suspend fun unavailableReason(): String? {
        val config = config()
        return when (config.llm) {
            LlmEngine.NONE -> "No AI model is set up. Download one or point Tsumugi at your own server in Settings → AI."
            LlmEngine.LOCAL -> when {
                llmBridge == null -> "On-device AI isn't included in this build."
                localModel(config) == null -> "The selected model isn't downloaded yet."
                else -> null
            }
            LlmEngine.ENDPOINT -> if (config.endpointUrl.isBlank()) "Add your server's URL in Settings → AI." else null
        }
    }

    /** Recognizer for the configured engine, or null when the app should use the OS recognizer. */
    @Throws(Exception::class)
    suspend fun recognizer(): SpeechRecognizer? {
        val config = config()
        return lock.withLock {
            cachedRecognizer?.takeIf { it.first == config }?.second ?: buildRecognizer(config).also { cachedRecognizer = config to it }
        }
    }

    /** VOICEVOX when configured; null means the app speaks with the OS voices. */
    @Throws(Exception::class)
    suspend fun synthesizer(): Synthesizer? {
        val config = config()
        if (config.tts != TtsEngine.VOICEVOX || config.voicevoxUrl.isBlank()) return null
        return VoicevoxSynthesizer(platform.httpEngine(), config.voicevoxUrl, config.voicevoxSpeaker)
    }

    /** Frees the on-device model (memory warnings, leaving the speaking screens). */
    @Throws(Exception::class)
    suspend fun unload() {
        llmBridge?.unload()
        lock.withLock { cachedModel = null }
    }

    private fun build(config: AiConfig): LanguageModel? = when (config.llm) {
        LlmEngine.NONE -> null
        LlmEngine.LOCAL -> {
            val bridge = llmBridge
            val info = localModel(config)
            val path = info?.let { models?.modelPath(it) }
            if (bridge == null || info == null || path == null) null else LocalLlamaModel(bridge, info, path.toString())
        }
        LlmEngine.ENDPOINT -> if (config.endpointUrl.isBlank()) null else {
            OpenAICompatibleModel(platform.httpEngine(), config.endpointUrl, endpointKey, config.endpointModel)
        }
    }

    private fun localModel(config: AiConfig) = models?.let { m ->
        (config.localModelId?.let(m::model) ?: m.recommend(ModelKind.LLM, deviceRamGb))?.takeIf(m::isInstalled)
    }

    private fun buildRecognizer(config: AiConfig): SpeechRecognizer? = when (config.stt) {
        SttEngine.SYSTEM -> null
        SttEngine.WHISPER_LOCAL -> {
            val bridge = sttBridge
            val m = models
            val info = m?.let { (config.localSttModelId?.let(it::model) ?: it.recommend(ModelKind.STT, deviceRamGb))?.takeIf(it::isInstalled) }
            val path = info?.let { m.modelPath(it) }
            if (bridge == null || info == null || path == null) null else WhisperRecognizer(bridge, path.toString(), info.id)
        }
        SttEngine.WHISPER_ENDPOINT -> if (config.sttEndpointUrl.isBlank()) null else {
            WhisperEndpointRecognizer(platform.httpEngine(), config.sttEndpointUrl, endpointKey)
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(value: String?, default: E): E =
        value?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    companion object {
        const val MANIFEST = "models-manifest.json"
        const val LLM = "ai.llm"
        const val LOCAL_MODEL = "ai.local_model"
        const val ENDPOINT_URL = "ai.endpoint_url"
        const val ENDPOINT_MODEL = "ai.endpoint_model"
        const val STT = "ai.stt"
        const val LOCAL_STT_MODEL = "ai.local_stt_model"
        const val STT_ENDPOINT_URL = "ai.stt_endpoint_url"
        const val TTS = "ai.tts"
        const val VOICEVOX_URL = "ai.voicevox_url"
        const val VOICEVOX_SPEAKER = "ai.voicevox_speaker"
        private const val ENDPOINT_KEY = "ai.endpoint_key"
    }
}

