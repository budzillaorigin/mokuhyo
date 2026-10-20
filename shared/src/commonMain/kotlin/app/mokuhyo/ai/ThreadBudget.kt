package app.mokuhyo.ai

/** CPU threads per native engine (BRIEF_INTERVIEW_SPEED N4). */
data class ThreadCaps(val llm: Int, val asr: Int, val tts: Int)

/**
 * Splits the computer's cores between the model, speech recognition and the voice so that work on a turn's critical
 * path never oversubscribes them (N4). With streaming the voice synthesizes while the model writes, so model + voice fit
 * in the budget; recognition runs while the model is idle (or only doing deferrable background work), so recognition +
 * voice fit too. From six cores up one is left for audio capture/playback and the UI.
 */
object ThreadBudget {
    /** [cores]: physical cores (the engines' own default). [overrides] come from Settings; null/0 = automatic. */
    fun caps(cores: Int, overrides: ThreadCaps? = null): ThreadCaps {
        val c = cores.coerceAtLeast(1)
        val available = if (c >= 6) c - 1 else c
        val tts = when {
            available >= 8 -> 2
            else -> 1
        }
        val rest = (available - tts).coerceAtLeast(1)
        val auto = ThreadCaps(llm = rest.coerceAtMost(16), asr = rest.coerceIn(1, 8), tts = tts)
        fun pick(o: Int?, a: Int) = o?.takeIf { it > 0 }?.coerceIn(1, 64) ?: a
        return ThreadCaps(pick(overrides?.llm, auto.llm), pick(overrides?.asr, auto.asr), pick(overrides?.tts, auto.tts))
    }
}
