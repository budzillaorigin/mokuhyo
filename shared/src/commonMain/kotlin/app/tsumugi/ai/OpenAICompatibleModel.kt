package app.tsumugi.ai

import app.tsumugi.net.EndpointHealth
import app.tsumugi.net.NetTimeouts
import app.tsumugi.net.tsumugiHttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * "Use my own AI server" (BRIEF §7.3): any OpenAI-compatible `/v1/chat/completions` — Ollama (`/v1`),
 * LM Studio, llama-server, vLLM. Structured output tries `response_format: json_schema`, falls back to
 * `json_object`, then to prompt-only JSON, and remembers what the server accepted.
 */
class OpenAICompatibleModel(
    engine: HttpClientEngine,
    baseUrl: String,
    /** Sent only to [baseUrl] (CLAUDE.md rule 14): the LLM endpoint's own key, never another endpoint's. */
    private val apiKey: String?,
    private val model: String,
    /** Shared "recently unreachable" cache so a dead server fails fast (F-11). Null = always try. */
    private val health: EndpointHealth? = null,
    timeouts: NetTimeouts = NetTimeouts.CHAT,
) : LanguageModel {
    override val id: String = model
    override val isLocal: Boolean = false

    private val http = tsumugiHttpClient(engine, timeouts)
    private val json = Json { ignoreUnknownKeys = true }
    val baseUrl: String = normalize(baseUrl)
    private val engineLabel = "endpoint $model @ ${runCatching { Url(this.baseUrl).host }.getOrDefault(this.baseUrl)}"

    /** Strongest structured-output mode the server has accepted so far. */
    private var mode = StructuredMode.JSON_SCHEMA

    @Throws(Exception::class)
    override suspend fun complete(request: CompletionRequest): CompletionResult {
        while (true) {
            val useMode = if (request.jsonSchema == null) StructuredMode.NONE else mode
            health?.unreachable(baseUrl)?.let { throw AiException(it) }
            val response = try {
                http.post("$baseUrl/chat/completions") {
                    auth()
                    contentType(ContentType.Application.Json)
                    setBody(body(request, useMode).toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                health?.markDown(baseUrl)
                throw AiException("can't reach $baseUrl: ${e.message}", e)
            }
            health?.markUp(baseUrl)
            if (!response.status.isSuccess()) {
                // Servers without structured-output support reject the parameter: step down and retry.
                if (response.status.value in 400..422 && useMode != StructuredMode.NONE) {
                    mode = useMode.next()
                    continue
                }
                throw AiException("server returned ${response.status.value}: ${response.bodyAsText().take(200)}")
            }
            return parse(response)
        }
    }

    /** GET /v1/models: the model ids the server offers (used when saving endpoint settings). */
    @Throws(Exception::class)
    suspend fun probe(): List<String> {
        // An explicit "Test connection" always tries, and its outcome updates the cache.
        val response = try {
            http.get("$baseUrl/models") { auth() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            health?.markDown(baseUrl)
            throw AiException("can't reach $baseUrl: ${e.message}", e)
        }
        health?.markUp(baseUrl)
        if (!response.status.isSuccess()) throw AiException("server returned ${response.status.value}")
        return json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]?.jsonArray
            ?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        apiKey?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    private fun body(request: CompletionRequest, mode: StructuredMode): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", JsonArray(request.messages.map { m ->
            buildJsonObject {
                put("role", m.role.name.lowercase())
                put("content", m.content)
            }
        }))
        put("max_tokens", request.maxTokens)
        put("temperature", request.temperature)
        put("stream", false)
        if (request.stop.isNotEmpty()) put("stop", JsonArray(request.stop.map(::JsonPrimitive)))
        val schema = request.jsonSchema
        when {
            schema == null || mode == StructuredMode.NONE -> Unit
            mode == StructuredMode.JSON_SCHEMA -> put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject {
                    put("name", "output")
                    put("strict", true)
                    put("schema", schema.toJson())
                })
            })
            else -> put("response_format", buildJsonObject { put("type", "json_object") })
        }
    }

    private suspend fun parse(response: HttpResponse): CompletionResult {
        val root = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }
            .getOrElse { throw AiException("unreadable response from $baseUrl") }
        val text = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw AiException("response had no message content")
        val tokens = root["usage"]?.jsonObject?.get("completion_tokens")?.jsonPrimitive?.intOrNull
        return CompletionResult(text.trim(), engineLabel, tokens)
    }

    private enum class StructuredMode {
        JSON_SCHEMA, JSON_OBJECT, NONE;

        fun next() = entries[(ordinal + 1).coerceAtMost(entries.lastIndex)]
    }

    companion object {
        /** Accepts "http://host:11434", "http://host:11434/", or ".../v1" and returns ".../v1". */
        fun normalize(url: String): String {
            val trimmed = url.trim().trimEnd('/')
            return if (trimmed.endsWith("/v1")) trimmed else "$trimmed/v1"
        }
    }
}
