package com.codingpit.muviss.feature.search.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.collection.api.ListSummary
import com.codingpit.muviss.feature.collection.api.ListsApi
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddToListUiState(
    val loading: Boolean = true,
    val lists: List<ListSummary> = emptyList(),
    /** Ids of [lists] that already contain the title being edited — drives the checkmark rows. */
    val memberListIds: Set<String> = emptySet(),
)

/**
 * Drives the Detail screen's "Add to list" affordance (EPIC 17) via
 * [ListsApi] — collection's public contract, never its domain/data/ui, per
 * ADR 0004 — the same way [DetailViewModel] uses `CollectionApi`. A
 * separate `ViewModel` rather than folded into [DetailViewModel]: the
 * sheet is its own semi-independent UI surface, and adding [ListsApi] as a
 * seventh [DetailViewModel] constructor parameter would push it past
 * detekt's `LongParameterList` threshold (it's already at six).
 */
class AddToListViewModel(
    private val mediaId: MediaId,
    private val listsApi: ListsApi,
) : ViewModel() {

    private val _state = MutableStateFlow(AddToListUiState())
    val state: StateFlow<AddToListUiState> = _state.asStateFlow()

    init {
        combine(listsApi.observeLists(), listsApi.observeListMembership(mediaId)) { lists, memberIds ->
            AddToListUiState(loading = false, lists = lists, memberListIds = memberIds)
        }.onEach { _state.value = it }.launchIn(viewModelScope)
    }

    /** Adds the title to [listId], or removes it if already a member. */
    fun toggle(listId: String) {
        viewModelScope.launch {
            if (listId in _state.value.memberListIds) {
                listsApi.removeFromList(listId, mediaId)
            } else {
                listsApi.addToList(listId, mediaId)
            }
        }
    }

    /** Creates a new list named [name] and immediately adds the title to it — the "create-new-list inline" flow. */
    fun createAndAdd(name: String) {
        viewModelScope.launch {
            runCatching { listsApi.createList(name) }.onSuccess { listId -> listsApi.addToList(listId, mediaId) }
        }
    }
}
