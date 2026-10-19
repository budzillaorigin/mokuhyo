package app.mokuhyo.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One issued inference at a time. Native cancellation can only touch the job owning [execution]. */
internal class InferenceScheduler {
    private class Preempted : CancellationException("Feedback deferred for speech")
    private val state = Mutex()
    private val execution = Mutex()
    private var background: Job? = null
    private var waitingForeground = 0

    suspend fun <T> run(feedback: Boolean, deferred: () -> T, block: suspend () -> T): T {
        if (!feedback) state.withLock {
            waitingForeground++
            background?.cancel(Preempted())
        }
        try {
            return coroutineScope {
                val owner = currentCoroutineContext().job
                execution.withLock {
                    if (feedback) {
                        val admitted = state.withLock {
                            if (waitingForeground > 0) false else { background = owner; true }
                        }
                        if (!admitted) return@withLock deferred()
                    }
                    try {
                        currentCoroutineContext().ensureActive()
                        block()
                    } finally {
                        withContext(NonCancellable) { state.withLock { if (background === owner) background = null } }
                    }
                }
            }
        } catch (e: Preempted) {
            // Only the scheduler's own cancellation is converted; navigation/user cancellation still propagates.
            return deferred()
        } finally {
            if (!feedback) withContext(NonCancellable) { state.withLock { waitingForeground-- } }
        }
    }
}
