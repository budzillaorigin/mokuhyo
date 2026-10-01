package app.mokuhyo.desktop

import app.mokuhyo.ai.DownloadProgress
import app.mokuhyo.ai.ModelInfo
import app.mokuhyo.ai.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Background model downloads with a progress pill in the title bar (BRIEF §6.1): resumable (pause keeps the .part
 * file), hash-verified, cancellable. One job per model.
 */
class DownloadCenter(private val scope: CoroutineScope, private val models: ModelManager) {
    sealed interface State {
        val model: ModelInfo

        data class Running(override val model: ModelInfo, val done: Long, val total: Long, val verifying: Boolean = false) : State {
            val fraction: Float get() = if (total <= 0) 0f else (done.toDouble() / total).toFloat()
        }
        data class Paused(override val model: ModelInfo, val done: Long, val total: Long) : State
        data class Failed(override val model: ModelInfo, val message: String, val retryable: Boolean) : State
        data class Done(override val model: ModelInfo) : State
    }

    private val jobs = mutableMapOf<String, Job>()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    fun start(model: ModelInfo) {
        if (jobs[model.id]?.isActive == true) return
        set(State.Running(model, models.bytesOnDisk(model), model.totalBytes))
        jobs[model.id] = scope.launch {
            models.download(model).collect { p ->
                when (p) {
                    is DownloadProgress.Downloading -> set(State.Running(model, p.bytesDone, p.bytesTotal))
                    is DownloadProgress.Verifying -> set(State.Running(model, model.totalBytes, model.totalBytes, verifying = true))
                    is DownloadProgress.Done -> set(State.Done(model))
                    is DownloadProgress.Failed -> set(State.Failed(model, p.message, p.retryable))
                }
            }
        }
    }

    fun pause(model: ModelInfo) {
        jobs.remove(model.id)?.cancel()
        set(State.Paused(model, models.bytesOnDisk(model), model.totalBytes))
    }

    fun clear(model: ModelInfo) {
        jobs.remove(model.id)?.cancel()
        _states.update { it - model.id }
    }

    private fun set(state: State) = _states.update { it + (state.model.id to state) }
}
