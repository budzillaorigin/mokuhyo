package app.mokuhyo.opi

import app.mokuhyo.exam.IlrLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Rater calibration (BRIEF_PHASE8 N-04): how often the model's OPI estimate agrees with instructor ratings of the same
 * recordings, per language and tier, and the confidence band the app shows next to an estimate. The table ships as
 * `models/calibration.json`, written by tools/models/calibrate.py from instructor-rated samples
 * (tools/sources/calibration/<lang>/). Fixture samples never count: without real samples the band says "uncalibrated".
 */
object Calibration {
    @Serializable
    data class Entry(val lang: String, val tier: String, val model: String, val n: Int, val exact: Int, val within1: Int, val fixture: Boolean = false) {
        val exactRate: Double get() = if (n == 0) 0.0 else exact.toDouble() / n
        val within1Rate: Double get() = if (n == 0) 0.0 else within1.toDouble() / n
    }

    @Serializable
    data class Table(val format: String = "mokuhyo-calibration/1", val generated: String = "", val entries: List<Entry> = emptyList()) {
        fun entry(lang: String, tier: String): Entry? = entries.firstOrNull { it.lang == lang && it.tier == tier && !it.fixture }

        companion object {
            private val json = Json { ignoreUnknownKeys = true }

            fun parse(text: String): Table = json.decodeFromString(serializer(), text)
        }
    }

    /** At least this many real samples before a band is shown. */
    const val MIN_SAMPLES = 5

    /** Exact and within-one-step agreement of (model, human) ILR labels; unparseable labels count as disagreement. */
    fun agreement(pairs: List<Pair<String, String>>): Pair<Int, Int> {
        var exact = 0
        var within = 0
        pairs.forEach { (m, h) ->
            val a = IlrLevel.lowerRange.indexOf(IlrLevel.parse(m))
            val b = IlrLevel.lowerRange.indexOf(IlrLevel.parse(h))
            if (a >= 0 && b >= 0) {
                if (a == b) exact++
                if (kotlin.math.abs(a - b) <= 1) within++
            }
        }
        return exact to within
    }

    data class Band(val low: String, val high: String, val calibrated: Boolean, val sentence: String)

    /**
     * The band around [estimate]: one ILR step either side when within-one agreement is at least 80 %, two steps when
     * lower; "uncalibrated" (no band) without enough real samples.
     */
    fun band(estimate: String, entry: Entry?, languageName: String, tierName: String): Band {
        if (entry == null || entry.n < MIN_SAMPLES) return Band(estimate, estimate, false,
            "Uncalibrated: no instructor-rated samples for $languageName on $tierName yet, so this estimate has no confidence band.")
        val levels = IlrLevel.lowerRange
        val i = levels.indexOf(IlrLevel.parse(estimate)).coerceAtLeast(0)
        val w = if (entry.within1Rate >= 0.8) 1 else 2
        val low = levels[(i - w).coerceAtLeast(0)].label
        val high = levels[(i + w).coerceAtMost(levels.lastIndex)].label
        return Band(low, high, true, "Based on ${entry.n} calibrated samples for $languageName on $tierName " +
            "(exact agreement ${(entry.exactRate * 100).toInt()} %, within one step ${(entry.within1Rate * 100).toInt()} %).")
    }
}
