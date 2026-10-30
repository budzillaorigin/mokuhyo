package app.mokuhyo.speech

/**
 * How long the learner may pause before capture ends their turn (QC 2026-10-30 V7): the VAD's silence endpoint.
 * [NORMAL] is today's 1.5 s. Used by the optional conversation mode; manual practice and tests keep the default [Vad].
 */
enum class CapturePatience(val wire: String, val silenceStopMs: Int, val label: String) {
    SHORT("short", 1_000, "Short (1 s)"),
    NORMAL("normal", 1_500, "Normal (1.5 s)"),
    LONG("long", 2_500, "Long (2.5 s)"),
    VERY_LONG("very_long", 3_500, "Very long (3.5 s)"),
    ;

    fun vad(): Vad = Vad(silenceStopMs = silenceStopMs)

    companion object {
        fun of(wire: String?): CapturePatience? = entries.firstOrNull { it.wire == wire }

        /** A persona's patience (1–5) as how long they wait: brisk personas end the turn sooner, patient ones wait longer. */
        fun forPersona(patience: Int): CapturePatience = when {
            patience <= 2 -> SHORT
            patience == 3 -> NORMAL
            patience == 4 -> LONG
            else -> VERY_LONG
        }
    }
}
