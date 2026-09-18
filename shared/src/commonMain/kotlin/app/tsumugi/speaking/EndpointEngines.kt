package app.tsumugi.speaking

import app.tsumugi.ai.OpenAICompatibleModel
import app.tsumugi.ai.VoicevoxSynthesizer
import app.tsumugi.ai.WhisperEndpointRecognizer
import app.tsumugi.net.EndpointHealth
import app.tsumugi.platform.Secrets
import io.ktor.client.engine.HttpClientEngine

/**
 * Builds the engines that talk to the learner's own servers, each with **its own** key from the keychain/keystore
 * (CLAUDE.md rule 14, F-12): the LLM key goes only to the LLM endpoint, the STT key only to the Whisper endpoint,
 * the TTS key only to VOICEVOX. All share one [EndpointHealth] so a dead host fails fast everywhere (F-11).
 */
class EndpointEngines(
    private val engine: () -> HttpClientEngine,
    private val secrets: Secrets,
    val health: EndpointHealth = EndpointHealth(),
) {
    fun llm(config: AiConfig): OpenAICompatibleModel? =
        if (config.endpointUrl.isBlank()) null
        else OpenAICompatibleModel(engine(), config.endpointUrl, secrets.get(LLM_KEY), config.endpointModel, health)

    fun recognizer(config: AiConfig): WhisperEndpointRecognizer? =
        if (config.sttEndpointUrl.isBlank()) null
        else WhisperEndpointRecognizer(engine(), config.sttEndpointUrl, secrets.get(STT_KEY), health = health)

    fun synthesizer(config: AiConfig): VoicevoxSynthesizer? =
        if (config.voicevoxUrl.isBlank()) null
        else VoicevoxSynthesizer(engine(), config.voicevoxUrl, config.voicevoxSpeaker, secrets.get(TTS_KEY), health)

    companion object {
        /** Keychain/keystore names. The LLM key keeps its v1 name so existing installs keep working. */
        const val LLM_KEY = "ai.endpoint_key"
        const val STT_KEY = "ai.stt_endpoint_key"
        const val TTS_KEY = "ai.tts_endpoint_key"
    }
}
