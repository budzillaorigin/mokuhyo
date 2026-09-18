package app.tsumugi.jp

enum class PitchPattern { HEIBAN, ATAMADAKA, NAKADAKA, ODAKA }

/**
 * Tokyo-dialect pitch accent for a word of [moraCount] morae with the drop after mora [downstep]
 * (0 = heiban, no drop).
 */
data class PitchAccent(val downstep: Int, val moraCount: Int) {

    val pattern: PitchPattern
        get() = when {
            downstep == 0 -> PitchPattern.HEIBAN
            downstep == 1 -> PitchPattern.ATAMADAKA
            downstep >= moraCount -> PitchPattern.ODAKA
            else -> PitchPattern.NAKADAKA
        }

    /**
     * High (true) / low (false) per mora, plus one trailing entry for a following particle
     * (length = moraCount + 1). First mora is low unless the accent is on it; after the downstep all low.
     */
    fun heights(): List<Boolean> = List(moraCount + 1) { i ->
        val position = i + 1
        when (downstep) {
            0 -> position != 1
            1 -> position == 1
            else -> position in 2..downstep
        }
    }
}

object Pitch {
    /** Parses Kanjium-style accent lists: "0,2" → [0, 2]. Blank and malformed parts are ignored. */
    fun parse(accents: String): List<Int> =
        accents.split(',').mapNotNull { it.trim().toIntOrNull() }

    fun accent(reading: String, downstep: Int): PitchAccent = PitchAccent(downstep, Mora.count(reading))
}
