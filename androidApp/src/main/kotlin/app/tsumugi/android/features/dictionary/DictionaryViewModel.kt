package app.tsumugi.android.features.dictionary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.dictionary.DictionaryRepository
import app.tsumugi.dictionary.SearchResults
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

@OptIn(FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DictionaryViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as TsumugiApplication).graph

    private val _state = MutableStateFlow<DictionaryState>(DictionaryState.Loading)
    val state: StateFlow<DictionaryState> = _state.asStateFlow()

    val query = MutableStateFlow("")

    private val _results = MutableStateFlow(SearchResults.EMPTY)
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = graph.dictionary()?.let { DictionaryState.Ready(it) } ?: DictionaryState.NotInstalled
        }
        query.debounce(120).distinctUntilChanged()
            .mapLatest { q -> (state.value as? DictionaryState.Ready)?.repository?.search(q) ?: SearchResults.EMPTY }
            .onEach { _results.value = it }
            .launchIn(viewModelScope)
    }
}
