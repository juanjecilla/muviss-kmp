package com.codingpit.muviss.feature.collection.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.crash.reportFailure
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.models.toUserMessage
import kotlinx.coroutines.Job
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
     * The list just deleted, for as long as its Undo snackbar is up. The delete
     * is already on disk — a delete held back until the snackbar went would be
     * lost with the screen or the process (#73) — so Undo restores it.
     */
    val lastDeleted: DeletedList? = null,
    /** One-shot snackbar text, e.g. a delete or restore that failed; cleared by [ListsViewModel.consumeMessage]. */
    val message: String? = null,
)

/** A deleted list and the stamp its delete wrote, which the restore needs. */
data class DeletedList(val list: MediaList, val deletedAtEpochMs: Long)

/**
 * Drives the Lists segment of the Collection screen (EPIC 17): observes
 * every user-defined list (with live entry counts) and exposes
 * create/rename/delete. Mirrors [CollectionViewModel]'s shape.
 */
class ListsViewModel(private val listsUseCases: ListsUseCases) : ViewModel() {

    private val _state = MutableStateFlow(ListsUiState())
    val state: StateFlow<ListsUiState> = _state.asStateFlow()

    private var observation: Job? = null

    init {
        observe()
    }

    /** Re-subscribes after a failed load (EPIC 30, #73): a Flow that threw is finished and will not emit again on its own. */
    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        observe()
    }

    private fun observe() {
        observation?.cancel()
        observation = listsUseCases.observeLists()
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

    /** Deletes [list] now and offers Undo; a failure says so instead of leaving the row in place silently (#73). */
    fun deleteList(list: MediaList) {
        viewModelScope.launchReporting {
            runCatching { listsUseCases.delete(list.id) }.reportFailure()
                .onSuccess { stamp -> _state.update { it.copy(lastDeleted = DeletedList(list, stamp)) } }
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage(DELETE_FAILED)) } }
        }
    }

    /** Undo: puts back the list and exactly the entries its delete took with it. */
    fun undoDelete(deleted: DeletedList) {
        _state.update { if (it.lastDeleted == deleted) it.copy(lastDeleted = null) else it }
        viewModelScope.launchReporting {
            runCatching { listsUseCases.restore(deleted.list.id, deleted.deletedAtEpochMs) }.reportFailure()
                .onFailure { e -> _state.update { it.copy(message = e.toUserMessage(RESTORE_FAILED)) } }
        }
    }

    /** The Undo snackbar for [deleted] went without Undo; the delete simply stands. */
    fun deleteUndoDismissed(deleted: DeletedList) = _state.update { if (it.lastDeleted == deleted) it.copy(lastDeleted = null) else it }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
        const val CREATE_FAILED = "Couldn't create the list."
        const val RENAME_FAILED = "Couldn't rename the list."
        const val DELETE_FAILED = "Couldn't delete the list."
        const val RESTORE_FAILED = "Couldn't bring the list back."
    }
}
