package app.mokuhyo.ai

import app.mokuhyo.net.NetTimeouts
import app.mokuhyo.net.mokuhyoHttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A model an existing Ollama server offers. [excludedReason] is set for rule-13 or rule-6 exclusions (greyed out). */
data class OllamaModel(val name: String, val family: String, val parameterSize: String, val excludedReason: String?)

/**
 * "Use my Ollama" (BRIEF §6.1): looks for an Ollama server on localhost only, never on the network. Off by default;
 * the app calls [detect] only when the learner opens Settings → AI. Short timeouts: a missing server must not stall
 * the screen.
 */
class OllamaDetector(engine: HttpClientEngine, private val baseUrl: String = DEFAULT_URL) {
    private val http = mokuhyoHttpClient(engine, NetTimeouts(connectMs = 1_500, requestMs = 3_000, socketMs = null))

    /** The server's models, or null when no server answers. */
    suspend fun detect(): List<OllamaModel>? = try {
        val response = http.get("$baseUrl/api/tags")
        if (!response.status.isSuccess()) null else parse(response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** OpenAI-compatible base URL for [OpenAICompatibleModel]. */
    val openAiBaseUrl: String get() = "$baseUrl/v1"

    companion object {
        const val DEFAULT_URL = "http://localhost:11434"

        fun parse(text: String): List<OllamaModel> =
            Json.parseToJsonElement(text).jsonObject["models"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val details = o["details"]?.jsonObject
                val family = details?.get("family")?.jsonPrimitive?.contentOrNull.orEmpty()
                val families = details?.get("families")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                OllamaModel(
                    name = name,
                    family = family,
                    parameterSize = details?.get("parameter_size")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    excludedReason = ModelPolicy.exclusion(name, listOf(family) + families),
                )
            }
    }
}

/** CLAUDE.md rules 6 and 13 applied to model names coming from outside the manifest (e.g. a local Ollama). */
object ModelPolicy {
    /** PRC-origin families and their derivatives (rule 13). Mirrors tools/gates/check_provenance.py. */
    private val prcOrigin = Regex(
        "qwen|deepseek|\\byi[-_:.]|01-ai|chatglm|\\bglm[-_:.\\d]|internlm|minicpm|baichuan|cosyvoice|gpt-?sovits|melo-?tts|" +
            "sensevoice|paraformer|funasr|fish-?speech|f5-?tts|hunyuan|ernie|moonshot|kimi|doubao|seed-?tts|spark-?tts|" +
            "index-?tts|megatts|chattts|telechat|skywork|aquila|xverse|codegeex",
        RegexOption.IGNORE_CASE,
    )

    /** Families whose terms aren't permissive (rule 6); the owner may opt them in explicitly. */
    private val nonPermissive = Regex("gemma|llama", RegexOption.IGNORE_CASE)

    /**
     * Why a model may not be used, or null. [architectures] are GGUF architecture families: a PRC-origin
     * architecture (e.g. `qwen2` under a distilled model's own name) marks a derivative and excludes it too, but
     * the license check reads only the [name], because many permissive models reuse the `llama` architecture.
     */
    fun exclusion(name: String, architectures: List<String> = emptyList()): String? = when {
        (listOf(name) + architectures).any { prcOrigin.containsMatchIn(it) } ->
            "Excluded: developed by an organization based in the PRC, or derived from such a model (owner policy)."
        nonPermissive.containsMatchIn(name) -> "Excluded by default: its license terms are not permissive (Gemma/Llama)."
        else -> null
    }
}
