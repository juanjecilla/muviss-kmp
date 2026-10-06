package com.codingpit.muviss.feature.triage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.triage.api.SkippedTitle
import com.codingpit.muviss.feature.triage.domain.ObserveSkippedUseCase
import com.codingpit.muviss.feature.triage.domain.RestoreDecisionUseCase
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class SkippedUiState(
    val loading: Boolean = true,
    val titles: List<SkippedTitle> = emptyList(),
    val error: String? = null,
)

/**
 * The way back from a mis-swipe once the undo snackbar is gone.
 *
 * Restoring here only soft-deletes the decision — a Skip saved nothing, so
 * there is nothing else to unwind (unlike the deck's undo, which also has to
 * reverse a collection save and its ticks).
 */
class SkippedViewModel(
    private val observeSkipped: ObserveSkippedUseCase,
    private val restoreDecision: RestoreDecisionUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SkippedUiState())
    val state: StateFlow<SkippedUiState> = _state.asStateFlow()

    private var observation: Job? = null

    init {
        observe()
    }

    /** Re-subscribes after a failed load (EPIC 30, #73): a Flow that threw is finished and will not emit again on its own. */
    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        observe()
    }

    // A failed read used to leave `loading = false, titles = []`, which the
    // screen rendered as "Nothing skipped" — a false empty state (#73).
    private fun observe() {
        observation?.cancel()
        observation = observeSkipped()
            .onEach { titles -> _state.update { it.copy(loading = false, titles = titles, error = null) } }
            .catch { e -> _state.update { it.copy(loading = false, error = e.toUserMessage(LOAD_FAILED)) } }
            .launchInReporting(viewModelScope)
    }

    private companion object {
        const val LOAD_FAILED = "Couldn't load skipped titles."
    }

    fun onRestore(mediaId: MediaId) {
        viewModelScope.launchReporting { restoreDecision(mediaId) }
    }
}
