package app.tsumugi.android.features.study

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.srs.PathStatus
import app.tsumugi.srs.Rating
import app.tsumugi.study.LessonSession
import app.tsumugi.study.LessonState
import app.tsumugi.study.ReviewSession
import app.tsumugi.study.ReviewState
import app.tsumugi.study.StatsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import app.tsumugi.android.ui.readable

/** Path status + stats for Today, the Reviews tab and Me. Call [refresh] when a screen appears. */
class StudyOverviewViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph

    private val _status = MutableStateFlow<PathStatus?>(null)
    val status: StateFlow<PathStatus?> = _status.asStateFlow()

    private val _stats = MutableStateFlow<StatsSnapshot?>(null)
    val stats: StateFlow<StatsSnapshot?> = _stats.asStateFlow()

    private val _pathMissing = MutableStateFlow(false)
    val pathMissing: StateFlow<Boolean> = _pathMissing.asStateFlow()

    fun refresh() = viewModelScope.launch {
        val path = graph.path()
        _pathMissing.value = path == null
        _status.value = path?.status()
        _stats.value = graph.stats.snapshot()
    }

    fun setVacation(on: Boolean) = viewModelScope.launch {
        graph.stats.setVacation(on)
        refresh()
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
/** One review session; [limit] is the Today review budget when launched from Today (G-01), else the default cap. */
class ReviewViewModel(app: Application, private val limit: Int = 500) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    private val session = MutableStateFlow<ReviewSession?>(null)

    val state: StateFlow<ReviewState?> = session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Starting the session failed (F-33): shown with a retry instead of an endless spinner. */
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** True while an answer is being recorded (F-09): the answer controls are disabled so a double tap can't submit twice. */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    /** An action failed after the session started (e.g. the database write); shown under the card with a retry. */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()
    private var lastAction: (suspend ReviewSession.() -> Unit)? = null

    init {
        load()
    }

    fun load() {
        _loadError.value = null
        viewModelScope.launch {
            try {
                session.value = graph.startReviews(limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _loadError.value = e.readable()
            }
        }
    }

    fun submit(answer: String) {
        if (_submitting.value) return
        _submitting.value = true
        act({ submit(answer) }) { _submitting.value = false }
    }

    fun retryAction() {
        val action = lastAction ?: return
        _actionError.value = null
        act(action)
    }

    fun next() = act({ next() })
    fun undo() = act({ undo() })
    fun reveal() = act({ reveal() })
    fun grade(rating: Rating) = act({ grade(rating) })
    fun wrapUp() = act({ wrapUp() })
    fun finish() = act({ finish() })

    override fun onCleared() {
        // A finished session is a good moment to sync (BRIEF §8.2 client algorithm, step 4).
        getApplication<TsumugiApplication>().appScope.launch { graph.syncIfConfigured() }
    }

    private fun act(block: suspend ReviewSession.() -> Unit, after: () -> Unit = {}) {
        val s = session.value ?: return after()
        viewModelScope.launch {
            try {
                s.block()
                _actionError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastAction = block
                _actionError.value = e.readable()
            } finally {
                after()
            }
        }
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LessonViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    private val session = MutableStateFlow<LessonSession?>(null)
    private val _empty = MutableStateFlow(false)
    val empty: StateFlow<Boolean> = _empty.asStateFlow()

    val state: StateFlow<LessonState?> = session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            val s = graph.startLessons()
            _empty.value = s == null
            session.value = s
        }
    }

    fun nextItem() = session.value?.nextItem()
    fun previousItem() = session.value?.previousItem()
    fun startQuiz() = session.value?.startQuiz()
    fun submit(answer: String) = session.value?.submit(answer)
    fun reviewItem() = session.value?.reviewItem()
    fun next() {
        val s = session.value ?: return
        viewModelScope.launch { s.next() }
    }

    fun saveMyStory(itemId: String, story: String) = viewModelScope.launch { graph.path()?.saveMyStory(itemId, story) }
}
