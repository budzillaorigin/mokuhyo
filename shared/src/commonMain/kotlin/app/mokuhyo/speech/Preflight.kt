package app.mokuhyo.speech

import kotlinx.coroutines.CancellationException
import kotlin.time.TimeSource

/**
 * The checklist that runs when the learner presses Start on a spoken interview (BRIEF_INTERVIEW_SPEED N4): the
 * microphone opens, the speakers play a tone, a voice exists for the language and its process is up, speech
 * recognition is loaded, and the model is loaded and warmed. Each step reports progress; a failed step blocks Start
 * with what to do about it. Something deliberately not installed (no Whisper model, no AI model) is "not needed", not
 * a failure: the interview then runs typed or guided, as it says.
 *
 * Every step's time goes to [TurnTimeline.warmup], never into a turn: warm-up is not answer latency.
 */
class Preflight(private val checks: List<Check>) {
    enum class Kind(val wire: String) { MICROPHONE("microphone"), OUTPUT("output"), VOICE("voice"), TTS("tts"), ASR("asr"), LLM("llm") }

    class Check(val kind: Kind, val title: String, val run: suspend () -> Result)

    sealed interface Result {
        data class Ok(val detail: String? = null) : Result
        data class NotNeeded(val why: String) : Result
        /** [action] tells the learner what to do (where in Settings, which permission, …). */
        data class Failed(val action: String) : Result
    }

    enum class State { PENDING, RUNNING, OK, NOT_NEEDED, FAILED }

    data class Item(val kind: Kind, val title: String, val state: State = State.PENDING, val message: String? = null, val ms: Long? = null)

    data class Report(val items: List<Item>) {
        /** The first failed step: Start stays blocked while there is one. */
        val blocking: Item? get() = items.firstOrNull { it.state == State.FAILED }
        val ready: Boolean get() = blocking == null && items.none { it.state == State.PENDING || it.state == State.RUNNING }
        fun state(kind: Kind): State? = items.firstOrNull { it.kind == kind }?.state
    }

    /**
     * Runs every check in order (all of them, so the learner sees the whole list), reporting each change through
     * [onProgress]. Cancelling stops at the running step and propagates.
     */
    suspend fun run(timeline: TurnTimeline? = null, onProgress: (Report) -> Unit = {}): Report {
        val items = checks.map { Item(it.kind, it.title) }.toMutableList()
        onProgress(Report(items.toList()))
        checks.forEachIndexed { i, check ->
            items[i] = items[i].copy(state = State.RUNNING)
            onProgress(Report(items.toList()))
            val start = TimeSource.Monotonic.markNow()
            val result = try {
                check.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.Failed("${check.title} failed: ${e.message ?: e::class.simpleName}.")
            }
            val ms = start.elapsedNow().inWholeMilliseconds
            timeline?.warmup(check.kind.wire, ms)
            items[i] = when (result) {
                is Result.Ok -> items[i].copy(state = State.OK, message = result.detail, ms = ms)
                is Result.NotNeeded -> items[i].copy(state = State.NOT_NEEDED, message = result.why, ms = ms)
                is Result.Failed -> items[i].copy(state = State.FAILED, message = result.action, ms = ms)
            }
            onProgress(Report(items.toList()))
        }
        return Report(items.toList())
    }
}
