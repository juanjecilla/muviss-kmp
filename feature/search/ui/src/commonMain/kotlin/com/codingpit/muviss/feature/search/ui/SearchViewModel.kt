package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.domain.TrendingUseCase
import com.codingpit.muviss.models.MediaSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<MediaSummary> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val showingTrending: Boolean = true,
)

/**
 * Drives the search screen: debounced querying, a trending fallback when the
 * query is blank, and success/error state. Progress is not touched here — this
 * feature is read-only discovery.
 */
class SearchViewModel(
    private val searchMedia: SearchMediaUseCase,
    private val trending: TrendingUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadTrending()
    }

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            loadTrending()
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(query)
        }
    }

    fun retry() {
        if (_state.value.query.isBlank()) loadTrending() else onQueryChange(_state.value.query)
    }

    private fun loadTrending() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            trending().fold(
                onSuccess = { r ->
                    _state.update { it.copy(loading = false, results = r, showingTrending = true) }
                },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } },
            )
        }
    }

    private suspend fun runSearch(query: String) {
        _state.update { it.copy(loading = true, error = null) }
        searchMedia(query).fold(
            onSuccess = { r ->
                _state.update { it.copy(loading = false, results = r, showingTrending = false) }
            },
            onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } },
        )
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
