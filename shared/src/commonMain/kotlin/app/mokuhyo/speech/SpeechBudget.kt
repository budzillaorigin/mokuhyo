package app.mokuhyo.speech

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * One response's time for audio to start (QC 2026-10-30 V6). Text-ready is the model's deadline; this is the
 * output-start deadline: every synthesis attempt and every fallback voice for the response shares what remains of
 * [totalMs], so an engine's own per-request timeout can never extend the wait. Once audio has started ([started]) the
 * budget no longer applies and the delivery policy of V10 takes over. Carried in the coroutine context, like the
 * timeline trace, so the voice service can read it without new parameters.
 */
class SpeechBudget(val totalMs: Long, private val nowMs: () -> Long = monotonicMs()) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<SpeechBudget> {
        fun monotonicMs(): () -> Long {
            val start = TimeSource.Monotonic.markNow()
            return { start.elapsedNow().inWholeMilliseconds }
        }

        /** The output-start budget for a response whose text deadline is [textDeadlineMs] (null = wait for the model). */
        fun forResponse(textDeadlineMs: Long?): Long = (textDeadlineMs ?: WAIT_FOR_MODEL_MS) + SYNTHESIS_MS

        /** Time for the first sentence's synthesis after the text deadline. */
        const val SYNTHESIS_MS = 4_000L

        /** "Always wait" for the model still caps the wait for audio. */
        const val WAIT_FOR_MODEL_MS = 26_000L
    }

    private val origin = nowMs()

    @kotlin.concurrent.Volatile
    var started: Boolean = false
        private set

    /** Audio began playing: from here on the budget no longer cuts anything off. */
    fun start() {
        started = true
    }

    /** Milliseconds left for audio to start; [Long.MAX_VALUE] once it has. */
    fun remainingMs(): Long = if (started) Long.MAX_VALUE else (totalMs - (nowMs() - origin)).coerceAtLeast(0)

    val exhausted: Boolean get() = remainingMs() == 0L
}

/** The calling coroutine's speech budget, if any. */
suspend fun currentSpeechBudget(): SpeechBudget? = currentCoroutineContext()[SpeechBudget]
