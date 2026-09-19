package app.tsumugi.android.features.dictionary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.android.ui.readable
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.InstantSearch
import app.tsumugi.dictionary.InstantSearchState
import app.tsumugi.dictionary.SearchResults
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DictionaryState {
    data object Loading : DictionaryState
    data object NotInstalled : DictionaryState
    data class Ready(val repository: DictionaryRepository) : DictionaryState
    /** Opening the dictionary failed (F-33); the screen offers a retry. */
    data class Failed(val message: String) : DictionaryState
}

/**
 * The dictionary screen's state. Results arrive as you type through the shared [InstantSearch] (BRIEF_V2 §6.15,
 * D-293): each keystroke goes to [InstantSearch.update], only the latest query publishes, and the old list stays up
 * while the next lookup runs.
 */
class DictionaryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph

    private val _state = MutableStateFlow<DictionaryState>(DictionaryState.Loading)
    val state: StateFlow<DictionaryState> = _state.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The last search failed (F-33): shown with a retry instead of stale or empty results. */
    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    /**
     * The same lookup as `AppGraph.instantSearch`, with failures caught here: a lookup that throws inside the search's
     * own scope would otherwise crash the app instead of showing the retry.
     */
    private val search = InstantSearch(
        search = { q ->
            try {
                val results = graph.dictionary()?.search(q) ?: SearchResults.EMPTY
                _searchError.value = null
                results
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _searchError.value = e.readable()
                SearchResults.EMPTY
            }
        },
    )

    /** What the list shows; its results lag the field while a lookup runs, so typing never flashes empty. */
    val instant: StateFlow<InstantSearchState> = search.state

    init {
        open()
    }

    fun setQuery(text: String) {
        _query.value = text
        if (text.isBlank()) _searchError.value = null
        search.update(text)
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
            // A query typed (or handed over) before the pack opened runs now.
            if (_state.value is DictionaryState.Ready && _query.value.isNotBlank()) search.submit(_query.value)
        }
    }

    fun retrySearch() {
        search.submit(_query.value)
    }

    override fun onCleared() {
        search.close()
    }
}
