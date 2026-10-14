package com.codingpit.muviss.feature.collection.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.crash.reportFailure
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaList
import com.codingpit.muviss.feature.collection.ui.generated.resources.Res
import com.codingpit.muviss.feature.collection.ui.generated.resources.error_generic
import com.codingpit.muviss.feature.collection.ui.generated.resources.list_create_failed
import com.codingpit.muviss.feature.collection.ui.generated.resources.list_delete_failed
import com.codingpit.muviss.feature.collection.ui.generated.resources.list_rename_failed
import com.codingpit.muviss.feature.collection.ui.generated.resources.list_restore_failed
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
    val error: UiText? = null,
    /** Non-null while the create-list dialog is open. */
    val creating: Boolean = false,
    /** The list currently being renamed, or null while no rename dialog is open. */
    val editing: MediaList? = null,
    /** Why the open create/rename dialog's last save failed; the dialog stays open until it works (#73). */
    val dialogError: UiText? = null,
    /**
     * The list just deleted, for as long as its Undo snackbar is up. The delete
     * is already on disk — a delete held back until the snackbar went would be
     * lost with the screen or the process (#73) — so Undo restores it.
     */
    val lastDeleted: DeletedList? = null,
    /** One-shot snackbar text, e.g. a delete or restore that failed; cleared by [ListsViewModel.consumeMessage]. */
    val message: UiText? = null,
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
            .catch { e -> _state.update { it.copy(loading = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) } }
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
                .onFailure { e -> _state.update { it.copy(dialogError = e.toUiText(UiText.Resource(Res.string.list_create_failed))) } }
        }
    }

    fun startEditing(list: MediaList) = _state.update { it.copy(editing = list, dialogError = null) }

    fun cancelEditing() = _state.update { it.copy(editing = null, dialogError = null) }

    fun renameList(name: String) {
        val listId = _state.value.editing?.id ?: return
        viewModelScope.launchReporting {
            runCatching { listsUseCases.rename(listId, name) }.reportFailure()
                .onSuccess { _state.update { it.copy(editing = null, dialogError = null) } }
                .onFailure { e -> _state.update { it.copy(dialogError = e.toUiText(UiText.Resource(Res.string.list_rename_failed))) } }
        }
    }

    /** Deletes [list] now and offers Undo; a failure says so instead of leaving the row in place silently (#73). */
    fun deleteList(list: MediaList) {
        viewModelScope.launchReporting {
            runCatching { listsUseCases.delete(list.id) }.reportFailure()
                .onSuccess { stamp -> _state.update { it.copy(lastDeleted = DeletedList(list, stamp)) } }
                .onFailure { e -> _state.update { it.copy(message = e.toUiText(UiText.Resource(Res.string.list_delete_failed))) } }
        }
    }

    /** Undo: puts back the list and exactly the entries its delete took with it. */
    fun undoDelete(deleted: DeletedList) {
        _state.update { if (it.lastDeleted == deleted) it.copy(lastDeleted = null) else it }
        viewModelScope.launchReporting {
            runCatching { listsUseCases.restore(deleted.list.id, deleted.deletedAtEpochMs) }.reportFailure()
                .onFailure { e -> _state.update { it.copy(message = e.toUiText(UiText.Resource(Res.string.list_restore_failed))) } }
        }
    }

    /** The Undo snackbar for [deleted] went without Undo; the delete simply stands. */
    fun deleteUndoDismissed(deleted: DeletedList) = _state.update { if (it.lastDeleted == deleted) it.copy(lastDeleted = null) else it }

    fun consumeMessage() = _state.update { it.copy(message = null) }
}
