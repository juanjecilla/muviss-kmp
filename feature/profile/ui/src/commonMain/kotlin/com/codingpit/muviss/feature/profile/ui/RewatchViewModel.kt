package com.codingpit.muviss.feature.profile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.profile.domain.ObserveRewatchStatsUseCase
import com.codingpit.muviss.feature.profile.domain.RewatchStats
import com.codingpit.muviss.feature.profile.domain.RewatchWindow
import com.codingpit.muviss.feature.profile.ui.generated.resources.Res
import com.codingpit.muviss.feature.profile.ui.generated.resources.error_rewatches
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class RewatchUiState(
    val loading: Boolean = true,
    val stats: RewatchStats = RewatchStats(),
    val error: UiText? = null,
) {
    val window: RewatchWindow get() = stats.window
}

/**
 * Drives the rewatch screen. The window is UI state, not stored: it changes
 * the query bound, and re-subscribing is the cheapest correct way to apply it
 * — hence [flatMapLatest] over a window flow rather than filtering in memory,
 * which could not narrow the "first play is global" rule anyway (ADR 0012).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RewatchViewModel(
    private val observeRewatchStats: ObserveRewatchStatsUseCase,
) : ViewModel() {

    private val window = MutableStateFlow(RewatchWindow.ALL_TIME)
    private val _state = MutableStateFlow(RewatchUiState())
    val state: StateFlow<RewatchUiState> = _state.asStateFlow()

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
        observation = window
            .flatMapLatest { observeRewatchStats(it) }
            .onEach { stats -> _state.update { it.copy(loading = false, stats = stats, error = null) } }
            .catch { error -> _state.update { it.copy(loading = false, error = error.toUiText(UiText.Resource(Res.string.error_rewatches))) } }
            .launchInReporting(viewModelScope)
    }

    fun onWindowSelected(selected: RewatchWindow) {
        window.value = selected
    }
}
