package app.mokuhyo.ai

import app.mokuhyo.exam.IlrLevel
import app.mokuhyo.opi.Calibration
import app.mokuhyo.opi.OpiInterviewerTurn
import app.mokuhyo.opi.OpiPhase
import app.mokuhyo.opi.Speaker
import app.mokuhyo.opi.Turn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * What the on-device timing check measured for one model (BRIEF_INTERVIEW_SPEED N2): time to the first token and
 * generation speed on the live interviewer prompt, with Whisper and the voice loaded alongside when [withSpeechLoaded].
 * [firstSentenceMs] is the estimate the recommendation uses: first token plus one short sentence of tokens.
 */
@Serializable
data class ModelTiming(
    val modelId: String,
    val firstTokenMs: Long,
    val tokensPerSecond: Double,
    val firstSentenceMs: Long,
    val loadMs: Long? = null,
    val withSpeechLoaded: Boolean = false,
    val backend: String = "",
    /** Epoch milliseconds. */
    val measuredAt: Long = 0,
) {
    val meetsBudget: Boolean get() = firstSentenceMs <= ModelTimingCheck.LIVE_BUDGET_MS

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val mapSerializer = MapSerializer(String.serializer(), serializer())

        /** The stored map (Settings `device.model.timings`): model id → its last timing. */
        fun parseAll(text: String?): Map<String, ModelTiming> =
            text?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }.orEmpty()

        fun encodeAll(all: Map<String, ModelTiming>): String = json.encodeToString(mapSerializer, all)
    }
}

/**
 * The short on-device timing check (N2): a fixed interviewer prompt with the live schema, run once to load and warm
 * the model (load time reported apart), then [runs] measured times. Pure over [LanguageModel]: the app supplies the
 * model with Whisper and the voice already loaded, so their memory and cores are part of what is measured.
 */
object ModelTimingCheck {
    /** The model's share of a live turn: first validated sentence in at most 2 s on the reference profile (D-045). */
    const val LIVE_BUDGET_MS = 2_000L

    /** Tokens in a short spoken sentence plus the JSON key before it (`{"utterance":"`). */
    const val SENTENCE_TOKENS = 24

    /** The fixed prompt: a level-check question in Spanish after two short turns (Latin script keeps tokens comparable). */
    fun prompt(): CompletionRequest {
        val task = OpiInterviewerTurn()
        val input = OpiInterviewerTurn.Input(
            "es", "Use usted with the candidate.", OpiPhase.LEVEL_CHECK, IlrLevel.L2,
            listOf(Turn(Speaker.PARTNER, "Buenos días. ¿Cómo se llama usted?"), Turn(Speaker.LEARNER, "Me llamo Ana. Trabajo en una oficina en Madrid.")),
            turnsInPhase = 0, domain = "work", questionType = OpiInterviewerTurn.questionType(OpiPhase.LEVEL_CHECK, IlrLevel.L2),
        )
        return CompletionRequest(AiGateway.withJsonContract(task.messages(input), task.schema), task.maxTokens, task.temperature, task.schema)
    }

    suspend fun measure(model: LanguageModel, runs: Int = 2, withSpeechLoaded: Boolean = false, backend: String = "", now: () -> Long = { 0L }): ModelTiming {
        val clock = TimeSource.Monotonic
        val load = clock.markNow()
        model.complete(prompt().copy(maxTokens = 8))
        val loadMs = load.elapsedNow().inWholeMilliseconds
        val samples = (1..runs.coerceAtLeast(1)).map {
            val start = clock.markNow()
            var first: Long? = null
            var last = 0L
            var pieces = 0
            model.complete(prompt().copy(onToken = {
                val t = start.elapsedNow().inWholeMilliseconds
                if (first == null) first = t
                last = t
                pieces++
            }))
            val total = start.elapsedNow().inWholeMilliseconds
            // Engines that don't stream (endpoints) report no tokens: then the whole reply is the first sentence.
            val ttft = first ?: total
            val tps = if (pieces > 1 && last > ttft) (pieces - 1) * 1000.0 / (last - ttft) else 0.0
            Triple(ttft, tps, total)
        }
        val ttft = samples.map { it.first }.sorted()[samples.size / 2]
        val tps = samples.map { it.second }.sorted()[samples.size / 2]
        val sentence = if (tps > 0) ttft + (SENTENCE_TOKENS * 1000 / tps).toLong() else samples.map { it.third }.sorted()[samples.size / 2]
        return ModelTiming(model.id, ttft, kotlin.math.round(tps * 10) / 10, sentence, loadMs, withSpeechLoaded, backend, now())
    }
}

/**
 * A language's quality floor (N2): the lowest tier with instructor-calibrated evidence that its ratings hold up
 * (within one ILR step at least 80 % of the time over at least [Calibration.MIN_SAMPLES] real samples). Fixture
 * entries are not evidence. Without evidence the floor is "unverified": nothing is invented, and no tier is excluded.
 */
data class QualityFloor(val language: String, val tier: Tier?, val evidence: String) {
    val verified: Boolean get() = tier != null

    companion object {
        fun of(language: String, table: Calibration.Table): QualityFloor {
            val real = table.entries.filter { it.lang == language && !it.fixture && it.n >= Calibration.MIN_SAMPLES }
            val passing = real.filter { it.within1Rate >= 0.8 }.mapNotNull { Tier.of(it.tier) }.minOrNull()
            return when {
                passing != null -> QualityFloor(language, passing, "calibrated: ${real.first { it.tier == passing.id }.n} instructor-rated samples")
                real.isNotEmpty() -> QualityFloor(language, null, "unverified: no measured tier meets the agreement bar yet")
                else -> QualityFloor(language, null, "unverified: no instructor-calibrated evidence for this language yet")
            }
        }
    }
}
