package app.mokuhyo.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Whether the interview's current question has reached the learner (QC follow-up F2). A test answers only what was
 * heard: its answer controls open once the question has played to the end, or after the learner explicitly chose
 * to carry on as text practice. Pending, playing, failed and cancelled all keep them closed, and every new question
 * starts again from pending. Practice may always answer.
 */
class QuestionDelivery(private val test: Boolean) {
    enum class State { PENDING, PLAYING, HEARD, FAILED, CANCELLED }

    /** The question this state belongs to (its index in the transcript). */
    var question by mutableIntStateOf(-1)
        private set
    var state by mutableStateOf(State.PENDING)
        private set

    /** Why the question didn't play, for the learner; null when nothing went wrong. */
    var problem by mutableStateOf<String?>(null)
        private set

    /** A test that fell back to showing its questions: it becomes text practice. */
    var textFallback by mutableStateOf(false)
        private set

    // Once heard, a later Repeat that fails or is stopped doesn't take the question away again.
    private var heard by mutableStateOf(false)

    val canAnswer: Boolean get() = !test || textFallback || heard

    val playing: Boolean get() = state == State.PLAYING

    /** The question didn't play: show Repeat and, in a test that can't answer yet, Continue as text practice. */
    val needsRecovery: Boolean get() = state == State.FAILED || state == State.CANCELLED

    fun newQuestion(index: Int) {
        question = index
        state = State.PENDING
        problem = null
        heard = false
    }

    fun fallBackToText() {
        textFallback = true
    }

    /**
     * Speaks question [index] with [say]. Returns null without playing anything when it is already playing (Repeat
     * can't overlap) or the interview has moved on. The state is written however [say] ends, cancellation included.
     */
    suspend fun deliver(index: Int, say: suspend () -> Voice.Outcome): Voice.Outcome? {
        if (index != question || state == State.PLAYING) return null
        state = State.PLAYING
        problem = null
        var outcome: Voice.Outcome? = null
        try {
            outcome = say()
            return outcome
        } finally {
            if (index == question) when {
                outcome == null -> stopped()
                outcome.ok -> {
                    heard = true
                    state = State.HEARD
                }
                else -> {
                    state = State.FAILED
                    problem = outcome.problem
                }
            }
        }
    }

    /** The learner pressed Cancel: a question that hadn't finished playing counts as not heard. */
    fun stop() {
        if (state == State.PENDING || state == State.PLAYING) stopped()
    }

    private fun stopped() {
        state = if (heard) State.HEARD else State.CANCELLED
        problem = if (heard) null else STOPPED
    }

    companion object {
        const val STOPPED = "The question was stopped before it played."
    }
}
