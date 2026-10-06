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
)

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

    fun startCreating() = _state.update { it.copy(creating = true) }

    fun cancelCreating() = _state.update { it.copy(creating = false) }

    fun createList(name: String) {
        viewModelScope.launchReporting {
            runCatching { listsUseCases.create(name) }.reportFailure()
            _state.update { it.copy(creating = false) }
        }
    }

    fun startEditing(list: MediaList) = _state.update { it.copy(editing = list) }

    fun cancelEditing() = _state.update { it.copy(editing = null) }

    fun renameList(name: String) {
        val listId = _state.value.editing?.id ?: return
        viewModelScope.launchReporting {
            runCatching { listsUseCases.rename(listId, name) }.reportFailure()
            _state.update { it.copy(editing = null) }
        }
    }

    fun deleteList(list: MediaList) {
        viewModelScope.launchReporting { listsUseCases.delete(list.id) }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
