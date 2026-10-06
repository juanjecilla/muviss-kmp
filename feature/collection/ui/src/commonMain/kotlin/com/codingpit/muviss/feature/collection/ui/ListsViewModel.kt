package com.codingpit.muviss.feature.collection.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.crash.reportFailure
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.models.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class ListsUiState(
    val loading: Boolean = true,
    val lists: List<MediaList> = emptyList(),
    val error: String? = null,
    /** Non-null while the create-list dialog is open. */
    val creating: Boolean = false,
    /** The list currently being renamed, or null while no rename dialog is open. */
    val editing: MediaList? = null,
    /** Why the open create/rename dialog's last save failed; the dialog stays open until it works (#73). */
    val dialogError: String? = null,
    /**
     * A list the user deleted that is still waiting out its Undo snackbar.
     * It is hidden at once but only deleted when the snackbar goes without
     * Undo (#73), so taking it back needs no restore path in the repository.
     */
    val pendingDelete: MediaList? = null,
) {
    /** [lists] minus the one waiting out its Undo. */
    val visibleLists: List<MediaList> get() = lists.filterNot { it.id == pendingDelete?.id }
}

/**
 * Drives the Lists segment of the Collection screen (EPIC 17): observes
 * every user-defined list (with live entry counts) and exposes
 * create/rename/delete. Mirrors [CollectionViewModel]'s shape.
 */
class ListsViewModel(private val listsUseCases: ListsUseCases) : ViewModel() {

    private val _state = MutableStateFlow(ListsUiState())
    val state: StateFlow<ListsUiState> = _state.asStateFlow()

    init {
        listsUseCases.observeLists()
            .catch { e -> _state.update { it.copy(loading = false, error = e.toUserMessage(DEFAULT_ERROR)) } }
            .onEach { lists -> _state.update { it.copy(loading = false, lists = lists, error = null) } }
            .launchInReporting(viewModelScope)
    }

    fun startCreating() = _state.update { it.copy(creating = true, dialogError = null) }

    fun cancelCreating() = _state.update { it.copy(creating = false, dialogError = null) }

    /** Closes the dialog only once the list exists; a failure keeps it open and says why (#73). */
    fun createList(name: String) {
        viewModelScope.launchReporting {
            runCatching { listsUseCases.create(name) }.reportFailure()
                .onSuccess { _state.update { it.copy(creating = false, dialogError = null) } }
                .onFailure { e -> _state.update { it.copy(dialogError = e.toUserMessage(CREATE_FAILED)) } }
        }
    }

    fun startEditing(list: MediaList) = _state.update { it.copy(editing = list, dialogError = null) }

    fun cancelEditing() = _state.update { it.copy(editing = null, dialogError = null) }

    fun renameList(name: String) {
        val listId = _state.value.editing?.id ?: return
        viewModelScope.launchReporting {
            runCatching { listsUseCases.rename(listId, name) }.reportFailure()
                .onSuccess { _state.update { it.copy(editing = null, dialogError = null) } }
                .onFailure { e -> _state.update { it.copy(dialogError = e.toUserMessage(RENAME_FAILED)) } }
        }
    }

    /**
     * Hides [list] behind an Undo snackbar. A second delete while one is
     * pending commits the first: only one snackbar exists, so only one delete
     * can be undoable at a time.
     */
    fun deleteList(list: MediaList) {
        _state.value.pendingDelete?.let(::commitDelete)
        _state.update { it.copy(pendingDelete = list) }
    }

    fun undoDelete() = _state.update { it.copy(pendingDelete = null) }

    /** The Undo snackbar went without Undo: the delete happens now. */
    fun confirmDelete() {
        val list = _state.value.pendingDelete ?: return
        _state.update { it.copy(pendingDelete = null) }
        commitDelete(list)
    }

    private fun commitDelete(list: MediaList) {
        viewModelScope.launchReporting { listsUseCases.delete(list.id) }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
        const val CREATE_FAILED = "Couldn't create the list."
        const val RENAME_FAILED = "Couldn't rename the list."
    }
}
