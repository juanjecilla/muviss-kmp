package com.codingpit.muviss.feature.collection.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The five ways the library can be sliced. [FAVORITES] is orthogonal to status (ADR 0005). */
enum class CollectionFilter {
    NOT_STARTED,
    WATCHING,
    WATCHED,
    FINISHED,
    FAVORITES,
}

data class CollectionUiState(
    val loading: Boolean = true,
    val entries: List<CollectionEntry> = emptyList(),
    val filter: CollectionFilter = CollectionFilter.NOT_STARTED,
    val error: String? = null,
) {
    /** [entries] sliced by the selected tab. Status always comes from [CollectionEntry.status] — never a stored column. */
    val visibleEntries: List<CollectionEntry>
        get() = entries.filter { entry ->
            when (filter) {
                CollectionFilter.FAVORITES -> entry.favorite
                CollectionFilter.NOT_STARTED -> entry.status == WatchStatus.NOT_STARTED
                CollectionFilter.WATCHING -> entry.status == WatchStatus.WATCHING
                CollectionFilter.WATCHED -> entry.status == WatchStatus.WATCHED
                CollectionFilter.FINISHED -> entry.status == WatchStatus.FINISHED
            }
        }
}

/**
 * Drives the Collection screen: observes the saved library, slices it by
 * [CollectionFilter], and best-effort refreshes every saved title's snapshot
 * on screen entry.
 */
class CollectionViewModel(
    observeCollection: ObserveCollectionUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase,
    private val refreshSnapshots: RefreshCollectionSnapshotsUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    init {
        observeCollection()
            .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } }
            .onEach { entries -> _state.update { it.copy(loading = false, entries = entries, error = null) } }
            .launchIn(viewModelScope)
        refresh()
    }

    fun selectFilter(filter: CollectionFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun setFavorite(mediaId: MediaId, favorite: Boolean) {
        viewModelScope.launch { toggleFavorite(mediaId, favorite) }
    }

    fun refresh() {
        viewModelScope.launch { runCatching { refreshSnapshots() } }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
