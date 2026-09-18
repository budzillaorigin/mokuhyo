package app.tsumugi.ai

import app.tsumugi.net.EndpointHealth
import app.tsumugi.net.NetTimeouts
import app.tsumugi.net.tsumugiHttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/**
 * whisper.cpp provided by the apps. [transcribe] receives 16 kHz mono float samples in [-1, 1] and calls
 * `onDone(segmentsJson, null)` with segments as a JSON array — `[{"t0": 0, "t1": 1520, "text": "…"}, …]`,
 * times in milliseconds — or `onDone(null, errorMessage)`.
 */
interface LocalSttBridge {
    fun isLoaded(): Boolean
    fun load(modelPath: String, onDone: (String?) -> Unit)
    fun transcribe(samples: FloatArray, language: String, onDone: (String?, String?) -> Unit)
}

/**
 * Optional capability of a [LocalSttBridge]: stop the running transcription. The bridge then calls the pending
 * `onDone(null, "cancelled…")` (see [LocalLlmBridge.CANCELLED], tools/models/README.md). A separate interface so
 * existing Swift bridges keep compiling until they adopt it.
 */
interface CancellableSttBridge {
    fun cancel()
}

/** On-device Whisper through [LocalSttBridge]. */
class WhisperRecognizer(
    private val bridge: LocalSttBridge,
    private val modelPath: String? = null,
    private val modelName: String = "Whisper",
) : SpeechRecognizer {
    @Throws(Exception::class)
    override suspend fun transcribe(pcm16kMono: ShortArray, language: String): Transcript {
        if (!bridge.isLoaded()) {
            val path = modelPath ?: throw AiException("speech model is not downloaded")
            suspendCancellableCoroutine { cont ->
                bridge.load(path) { error ->
                    if (!cont.isActive) return@load
                    if (error == null) cont.resume(Unit) else cont.resumeWithException(AiException("couldn't load speech model: $error"))
                }
            }
        }
        val samples = FloatArray(pcm16kMono.size) { pcm16kMono[it] / 32768f }
        val jsonText = suspendCancellableCoroutine { cont ->
            // Leaving the screen (or a timeout) cancels the coroutine: stop the native work too, and ignore the
            // late callback (F-41).
            cont.invokeOnCancellation { (bridge as? CancellableSttBridge)?.cancel() }
            bridge.transcribe(samples, language) { result, error ->
                if (!cont.isActive) return@transcribe
                when {
                    result != null -> cont.resume(result)
                    LocalLlmBridge.isCancelled(error) -> cont.resumeWithException(AiCancelledException(error ?: LocalLlmBridge.CANCELLED))
                    else -> cont.resumeWithException(AiException(error ?: "transcription failed"))
                }
            }
        }
        val segments = parseSegments(jsonText)
        return Transcript(segments.joinToString("") { it.text }.trim(), segments, "on-device $modelName")
    }

    companion object {
        fun parseSegments(jsonText: String): List<TranscriptSegment> =
            Json.parseToJsonElement(jsonText).jsonArray.map { e ->
                val o = e.jsonObject
                TranscriptSegment(
                    o["t0"]?.jsonPrimitive?.longOrNull ?: 0,
                    o["t1"]?.jsonPrimitive?.longOrNull ?: 0,
                    o["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                )
            }
    }
}

/** Whisper-compatible `/v1/audio/transcriptions` on the learner's own server (optional, BRIEF §7.3). */
class WhisperEndpointRecognizer(
    engine: HttpClientEngine,
    baseUrl: String,
    /** The STT endpoint's own key (`AiService.sttEndpointKey`), never the LLM key (CLAUDE.md rule 14). */
    private val apiKey: String?,
    private val model: String = "whisper-1",
    private val health: EndpointHealth? = null,
    timeouts: NetTimeouts = NetTimeouts.STT,
) : SpeechRecognizer {
    private val http = tsumugiHttpClient(engine, timeouts)
    private val base = OpenAICompatibleModel.normalize(baseUrl)

    @Throws(Exception::class)
    override suspend fun transcribe(pcm16kMono: ShortArray, language: String): Transcript {
        val wav = Wav.encode(pcm16kMono, SAMPLE_RATE)
        health?.unreachable(base)?.let { throw AiException(it) }
        val response = try {
            http.submitFormWithBinaryData(
                url = "$base/audio/transcriptions",
                formData = formData {
                    append("model", model)
                    append("language", language)
                    append("response_format", "verbose_json")
                    append("file", wav, Headers.build {
                        append(HttpHeaders.ContentType, "audio/wav")
                        append(HttpHeaders.ContentDisposition, "filename=\"speech.wav\"")
                    })
                },
            ) { apiKey?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            health?.markDown(base)
            throw AiException("can't reach $base: ${e.message}", e)
        }
        health?.markUp(base)
        if (!response.status.isSuccess()) throw AiException("server returned ${response.status.value}")
        val root = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val segments = (root["segments"] as? kotlinx.serialization.json.JsonArray)?.map { e ->
            val o = e.jsonObject
            TranscriptSegment(
                ((o["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).roundToLong(),
                ((o["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).roundToLong(),
                o["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            )
        }.orEmpty()
        val text = root["text"]?.jsonPrimitive?.contentOrNull?.trim() ?: segments.joinToString("") { it.text }
        return Transcript(text, segments, "endpoint $model")
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/**
 * Self-hosted VOICEVOX engine (free, optional): `POST /audio_query` then `POST /synthesis`. Bounded by
 * [NetTimeouts.VOICEVOX_SYNTH] (15 s); [probe] by [NetTimeouts.VOICEVOX_PROBE] (3 s). After a failure the host is
 * skipped for a minute ([EndpointHealth]) so the system voice speaks immediately.
 */
class VoicevoxSynthesizer(
    engine: HttpClientEngine,
    baseUrl: String,
    private val speaker: Int,
    /** The TTS endpoint's own key (`AiService.ttsEndpointKey`), e.g. for a reverse proxy; never the LLM key. */
    private val apiKey: String? = null,
    private val health: EndpointHealth? = null,
    timeouts: NetTimeouts = NetTimeouts.VOICEVOX_SYNTH,
    probeTimeouts: NetTimeouts = NetTimeouts.VOICEVOX_PROBE,
) : Synthesizer {
    private val http = tsumugiHttpClient(engine, timeouts)
    private val probeHttp = tsumugiHttpClient(engine, probeTimeouts)
    private val base = baseUrl.trim().trimEnd('/')

    /** True when the engine answers `GET /version` within the probe timeout. */
    @Throws(Exception::class)
    suspend fun probe(): Boolean = try {
        probeHttp.get("$base/version") { auth() }.status.isSuccess().also { if (it) health?.markUp(base) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        health?.markDown(base)
        false
    }

    @Throws(Exception::class)
    override suspend fun synthesize(text: String, voice: String?, speed: Double): ByteArray? {
        val speakerId = voice?.toIntOrNull() ?: speaker
        health?.unreachable(base)?.let { throw AiException(it) }
        return try {
            val queryResponse = http.post("$base/audio_query") {
                auth()
                parameter("text", text)
                parameter("speaker", speakerId)
            }
            if (!queryResponse.status.isSuccess()) throw AiException("VOICEVOX audio_query returned ${queryResponse.status.value}")
            val query = Json.parseToJsonElement(queryResponse.bodyAsText()).jsonObject
            val tuned = JsonObject(query + ("speedScale" to JsonPrimitive(speed)))
            val audio = http.post("$base/synthesis") {
                auth()
                parameter("speaker", speakerId)
                contentType(ContentType.Application.Json)
                setBody(tuned.toString())
            }
            if (!audio.status.isSuccess()) throw AiException("VOICEVOX synthesis returned ${audio.status.value}")
            audio.bodyAsBytes().also { health?.markUp(base) }
        } catch (e: AiException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            health?.markDown(base)
            throw AiException("can't reach VOICEVOX at $base: ${e.message}", e)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth() {
        apiKey?.takeIf { it.isNotBlank() }?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }
}

/** Minimal 16-bit PCM WAV writer. */
object Wav {
    fun encode(samples: ShortArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val dataBytes = samples.size * 2
        val out = ByteArray(44 + dataBytes)
        fun str(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun i32(at: Int, v: Int) { for (i in 0 until 4) out[at + i] = (v ushr (8 * i)).toByte() }
        fun i16(at: Int, v: Int) { out[at] = v.toByte(); out[at + 1] = (v ushr 8).toByte() }
        str(0, "RIFF"); i32(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16); i16(20, 1); i16(22, channels); i32(24, sampleRate)
        i32(28, sampleRate * channels * 2); i16(32, channels * 2); i16(34, 16)
        str(36, "data"); i32(40, dataBytes)
        samples.forEachIndexed { i, s -> i16(44 + i * 2, s.toInt()) }
        return out
    }
}
