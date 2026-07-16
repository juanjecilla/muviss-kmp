package com.codingpit.muviss.feature.collection.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaListItem
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListContentsUiState(
    val loading: Boolean = true,
    val items: List<MediaListItem> = emptyList(),
    val error: String? = null,
)

/**
 * Drives one list's contents screen (EPIC 17): observes [listId]'s
 * (snapshot-joined, orphan-hidden — see `ListsRepository`'s KDoc) items and
 * exposes removing one. [listId] is a Koin-injected parameter (see
 * `ListContentsRoute`/`collectionSection`), the same pattern
 * search:ui's `DetailViewModel` uses for its media id.
 */
class ListContentsViewModel(
    private val listId: String,
    private val listsUseCases: ListsUseCases,
) : ViewModel() {

    private val _state = MutableStateFlow(ListContentsUiState())
    val state: StateFlow<ListContentsUiState> = _state.asStateFlow()

    init {
        listsUseCases.observeContents(listId)
            .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } }
            .onEach { items -> _state.update { it.copy(loading = false, items = items, error = null) } }
            .launchIn(viewModelScope)
    }

    fun removeEntry(mediaId: MediaId) {
        viewModelScope.launch { listsUseCases.removeEntry(listId, mediaId) }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
