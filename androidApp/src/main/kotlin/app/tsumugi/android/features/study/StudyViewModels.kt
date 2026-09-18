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
import app.tsumugi.study.StatsService
import app.tsumugi.study.StatsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

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
        _stats.value = StatsService(graph.userDatabase, graph.srs(), graph.settings).snapshot()
    }

    fun setVacation(on: Boolean) = viewModelScope.launch {
        StatsService(graph.userDatabase, graph.srs(), graph.settings).setVacation(on)
        refresh()
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReviewViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph
    private val session = MutableStateFlow<ReviewSession?>(null)

    val state: StateFlow<ReviewState?> = session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { session.value = graph.startReviews() }
    }

    fun submit(answer: String) = act { submit(answer) }
    fun next() = act { next() }
    fun undo() = act { undo() }
    fun reveal() = act { reveal() }
    fun grade(rating: Rating) = act { grade(rating) }
    fun wrapUp() = act { wrapUp() }
    fun finish() = act { finish() }

    private fun act(block: suspend ReviewSession.() -> Unit) {
        val s = session.value ?: return
        viewModelScope.launch { s.block() }
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
