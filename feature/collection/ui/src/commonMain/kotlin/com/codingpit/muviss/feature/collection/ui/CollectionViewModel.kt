package com.codingpit.muviss.feature.collection.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.CollectionRefreshThrottle
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CancellationException
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

/** The three ways the (already-filtered) library can be ordered (EPIC 15). [RECENTLY_ADDED] is the default, matching the repository's natural order. */
enum class CollectionSort {
    RECENTLY_ADDED,
    RATING,
    TITLE,
}

data class CollectionUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val entries: List<CollectionEntry> = emptyList(),
    val filter: CollectionFilter = CollectionFilter.NOT_STARTED,
    val sort: CollectionSort = CollectionSort.RECENTLY_ADDED,
    val error: String? = null,
    /** One-shot snackbar text — currently only "the refresh failed". Cleared by [CollectionViewModel.consumeMessage]. */
    val message: String? = null,
) {
    /** [entries] sliced by the selected tab, then ordered by [sort]. Status always comes from [CollectionEntry.status] — never a stored column. */
    val visibleEntries: List<CollectionEntry>
        get() = entries.filter { it.matches(filter) }.sortedFor(sort)

    /** Chip count suffix ("Watching 12") for any filter, selected or not. */
    fun count(filter: CollectionFilter): Int = entries.count { it.matches(filter) }
}

private fun CollectionEntry.matches(filter: CollectionFilter): Boolean = when (filter) {
    CollectionFilter.FAVORITES -> favorite
    CollectionFilter.NOT_STARTED -> status == WatchStatus.NOT_STARTED
    CollectionFilter.WATCHING -> status == WatchStatus.WATCHING
    CollectionFilter.WATCHED -> status == WatchStatus.WATCHED
    CollectionFilter.FINISHED -> status == WatchStatus.FINISHED
}

/** Unrated entries always sort last under [CollectionSort.RATING], newest-first as the tiebreaker. */
private fun List<CollectionEntry>.sortedFor(sort: CollectionSort): List<CollectionEntry> = when (sort) {
    CollectionSort.RECENTLY_ADDED -> sortedByDescending { it.addedAtEpochMs }
    CollectionSort.RATING -> sortedWith(compareByDescending<CollectionEntry> { it.rating ?: -1 }.thenByDescending { it.addedAtEpochMs })
    CollectionSort.TITLE -> sortedBy { it.title.lowercase() }
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
    private val refreshThrottle: CollectionRefreshThrottle,
) : ViewModel() {

    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    init {
        observeCollection()
            .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } }
            .onEach { entries -> _state.update { it.copy(loading = false, entries = entries, error = null) } }
            .launchIn(viewModelScope)
        // Automatic, so it defers to the throttle; an explicit pull does not.
        if (refreshThrottle.claimAutomaticRefresh()) refresh(automatic = true)
    }

    fun selectFilter(filter: CollectionFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun selectSort(sort: CollectionSort) {
        _state.update { it.copy(sort = sort) }
    }

    fun setFavorite(mediaId: MediaId, favorite: Boolean) {
        viewModelScope.launch { toggleFavorite(mediaId, favorite) }
    }

    /**
     * Re-fetches every saved title's snapshot; also the pull-to-refresh
     * action, which is why the flag is cleared in a `finally` rather than
     * after a `runCatching`: `runCatching` swallows `CancellationException`
     * too, and any path that leaves `refreshing` true leaves the user staring
     * at a spinner that never stops.
     */
    fun refresh(automatic: Boolean = false) {
        if (!automatic) refreshThrottle.recordRefresh()
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                refreshSnapshots()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                _state.update { it.copy(message = e.message ?: REFRESH_FAILED) }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Acknowledges [CollectionUiState.message] once its snackbar has been shown. */
    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
        const val REFRESH_FAILED = "Couldn't refresh your library"
    }
}
