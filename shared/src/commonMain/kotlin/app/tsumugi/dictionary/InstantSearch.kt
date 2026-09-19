package app.tsumugi.dictionary

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * What the search field shows. [results] belong to [resultsFor] (the last query that finished), which lags [query]
 * while [searching]; the UI keeps showing the old list until the new one lands, so typing never flashes empty.
 */
data class InstantSearchState(
    val query: String,
    val resultsFor: String,
    val results: SearchResults,
    val searching: Boolean,
    /** Time the last finished lookup took (for the lookup budget, BRIEF §5.2). */
    val lookupMs: Long = 0,
)

/**
 * Instant-as-you-type dictionary results (BRIEF_V2 §6.15, Lorenzi's Jisho). Each keystroke calls [update]; the lookup
 * starts after [debounce] of quiet and any lookup still pending or running for an older query is cancelled, so only
 * the latest query's results are ever published. Lookups are local and within the per-lookup budget, so the debounce
 * only coalesces bursts of typing (IME composition, fast typists); it doesn't hide slowness.
 *
 * Observe [state] (Compose: collectAsState) or pass [onChange] (Swift: called on a background thread; hop to the
 * main actor). Call [close] when the screen goes away.
 */
class InstantSearch(
    private val search: suspend (String) -> SearchResults,
    private val debounce: Duration = DEFAULT_DEBOUNCE,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val onChange: ((InstantSearchState) -> Unit)? = null,
) {
    constructor(dictionary: DictionaryRepository, debounce: Duration = DEFAULT_DEBOUNCE, onChange: ((InstantSearchState) -> Unit)? = null) :
        this({ dictionary.search(it) }, debounce, CoroutineScope(SupervisorJob() + Dispatchers.Default), onChange)

    private val _state = MutableStateFlow(InstantSearchState("", "", SearchResults.EMPTY, false))
    val state: StateFlow<InstantSearchState> = _state.asStateFlow()
    private var job: Job? = null

    @Volatile
    private var generation = 0L

    /** The field changed to [text]. Blank text clears the results at once. */
    fun update(text: String) {
        job?.cancel()
        val gen = ++generation
        if (text.isBlank()) {
            publish(InstantSearchState(text, "", SearchResults.EMPTY, false))
            return
        }
        publish(_state.value.copy(query = text, searching = true))
        job = scope.launch {
            delay(debounce)
            val mark = TimeSource.Monotonic.markNow()
            val results = search(text)
            ensureActive() // a newer keystroke cancelled this lookup: drop its results
            if (gen == generation) publish(InstantSearchState(text, text, results, false, mark.elapsedNow().inWholeMilliseconds))
        }
    }

    /** Runs [text] now, without the debounce (the search key on the keyboard). */
    fun submit(text: String) {
        job?.cancel()
        val gen = ++generation
        publish(_state.value.copy(query = text, searching = text.isNotBlank()))
        if (text.isBlank()) return
        job = scope.launch {
            val mark = TimeSource.Monotonic.markNow()
            val results = search(text)
            ensureActive()
            if (gen == generation) publish(InstantSearchState(text, text, results, false, mark.elapsedNow().inWholeMilliseconds))
        }
    }

    /** Cancels any pending lookup and stops the scope this search owns. */
    fun close() {
        job?.cancel()
        scope.cancel()
    }

    private fun publish(s: InstantSearchState) {
        _state.value = s
        onChange?.invoke(s)
    }

    companion object {
        val DEFAULT_DEBOUNCE: Duration = 120.milliseconds
    }
}
