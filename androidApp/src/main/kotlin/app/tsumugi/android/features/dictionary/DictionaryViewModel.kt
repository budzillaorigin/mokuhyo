package app.tsumugi.android.features.dictionary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.readable
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

sealed interface DictionaryState {
    data object Loading : DictionaryState
    data object NotInstalled : DictionaryState
    data class Ready(val repository: DictionaryRepository) : DictionaryState
    /** Opening the dictionary failed (F-33); the screen offers a retry. */
    data class Failed(val message: String) : DictionaryState
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DictionaryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph

    private val _state = MutableStateFlow<DictionaryState>(DictionaryState.Loading)
    val state: StateFlow<DictionaryState> = _state.asStateFlow()

    val query = MutableStateFlow("")

    private val _results = MutableStateFlow(SearchResults.EMPTY)
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    /** The last search failed (F-33): shown with a retry instead of stale or empty results. */
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    /** Bumped by [retrySearch] so the same query runs again. */
    private val searchAttempt = MutableStateFlow(0)

    init {
        open()
        combine(query.debounce(120).distinctUntilChanged(), searchAttempt, state) { q, _, s -> q to s }
            .mapLatest { (q, s) ->
                val repository = (s as? DictionaryState.Ready)?.repository ?: return@mapLatest Result.success(SearchResults.EMPTY)
                try {
                    Result.success(repository.search(q))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            .onEach { r ->
                r.onSuccess { _results.value = it; _searchError.value = null }
                    .onFailure { _searchError.value = it.readable() }
            }
            .launchIn(viewModelScope)
    }

    fun open() {
        _state.value = DictionaryState.Loading
        viewModelScope.launch {
            _state.value = try {
                graph.dictionary()?.let { DictionaryState.Ready(it) } ?: DictionaryState.NotInstalled
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DictionaryState.Failed(e.readable())
            }
        }
    }

    fun retrySearch() {
        searchAttempt.value++
    }
}
