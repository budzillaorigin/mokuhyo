package app.mokuhyo.desktop

import app.mokuhyo.ai.AiGateway
import app.mokuhyo.ai.AiResult
import app.mokuhyo.ai.AiSettings
import app.mokuhyo.ai.OpenAICompatibleModel
import app.mokuhyo.net.NetTimeouts
import app.mokuhyo.opi.Calibration
import app.mokuhyo.opi.OpiRate
import app.mokuhyo.opi.Turn
import io.ktor.client.engine.java.Java
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `--calibrate --samples DIR --endpoint URL --tiers A=model,B=model… --out F [--fixture]` (BRIEF_PHASE8 N-04, run by
 * tools/models/calibrate.py): rates each instructor-rated sample with the app's own `opi_rate` prompt on each tier's
 * model and writes the agreement table (exact and within one ILR step) per language and tier.
 */
object CalibrateSpeaking {
    @Serializable
    data class Sample(val id: String, val lang: String, val transcript: List<Turn>, val humanRating: String, val rationale: String = "", val rater: String = "")

    @Serializable
    data class Rated(val id: String, val lang: String, val tier: String, val model: String, val human: String, val model_estimate: String?)

    @Serializable
    data class Report(val table: Calibration.Table, val rated: List<Rated>)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun run(args: Array<String>): Int = runBlocking {
        val dir = Smoke.arg(args, "--samples")?.let(::File) ?: return@runBlocking fail("--samples")
        val endpoint = Smoke.arg(args, "--endpoint") ?: return@runBlocking fail("--endpoint")
        val tiers = Smoke.arg(args, "--tiers")?.split(",")?.map { it.substringBefore("=") to it.substringAfter("=") } ?: return@runBlocking fail("--tiers")
        val out = File(Smoke.arg(args, "--out") ?: "calibration.json")
        val fixture = "--fixture" in args
        val samples = dir.walkTopDown().filter { it.isFile && it.extension == "json" }.map { json.decodeFromString(Sample.serializer(), it.readText()) }.toList()
        val rated = mutableListOf<Rated>()
        for ((tier, model) in tiers) {
            val gateway = AiGateway({ OpenAICompatibleModel(Java.create(), endpoint, null, model, timeouts = NetTimeouts(5_000, 300_000, null)) }, AiSettings(timeoutMs = 300_000))
            for (s in samples) {
                val est = when (val r = gateway.run(OpiRate(), OpiRate.Input(s.lang, s.transcript))) {
                    is AiResult.Ok -> OpiRate.normalize(OpiRate.Input(s.lang, s.transcript), r.value).estimate
                    else -> null
                }
                rated += Rated(s.id, s.lang, tier, model, s.humanRating, est)
                println("calibrate: ${s.lang} $tier ${s.id} human ${s.humanRating} model $est")
            }
        }
        val entries = rated.groupBy { it.lang to it.tier }.map { (k, rs) ->
            val (exact, within) = Calibration.agreement(rs.map { (it.model_estimate ?: "") to it.human })
            Calibration.Entry(k.first, k.second, rs.first().model, rs.size, exact, within, fixture)
        }
        out.parentFile?.mkdirs()
        out.writeText(json.encodeToString(Report.serializer(), Report(Calibration.Table(generated = java.time.LocalDate.now().toString(), entries = entries), rated)))
        println("calibrate: ${rated.size} ratings, ${entries.size} language/tier cells → $out")
        0
    }

    private fun fail(msg: String): Int {
        println("calibrate: FAIL missing $msg")
        return 1
    }
}
