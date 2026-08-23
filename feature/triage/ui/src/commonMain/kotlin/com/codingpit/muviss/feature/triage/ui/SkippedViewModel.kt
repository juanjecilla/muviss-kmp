package com.codingpit.muviss.feature.triage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.feature.triage.api.SkippedTitle
import com.codingpit.muviss.feature.triage.domain.ObserveSkippedUseCase
import com.codingpit.muviss.feature.triage.domain.RestoreDecisionUseCase
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SkippedUiState(
    val loading: Boolean = true,
    val titles: List<SkippedTitle> = emptyList(),
)

/**
 * The way back from a mis-swipe once the undo snackbar is gone.
 *
 * Restoring here only soft-deletes the decision — a Skip saved nothing, so
 * there is nothing else to unwind (unlike the deck's undo, which also has to
 * reverse a collection save and its ticks).
 */
class SkippedViewModel(
    observeSkipped: ObserveSkippedUseCase,
    private val restoreDecision: RestoreDecisionUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SkippedUiState())
    val state: StateFlow<SkippedUiState> = _state.asStateFlow()

    init {
        observeSkipped()
            .onEach { titles -> _state.update { it.copy(loading = false, titles = titles) } }
            .launchIn(viewModelScope)
    }

    fun onRestore(mediaId: MediaId) {
        viewModelScope.launch { restoreDecision(mediaId) }
    }
}
