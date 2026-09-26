package com.codingpit.muviss.feature.triage.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.triage.api.SnoozedTitle
import com.codingpit.muviss.feature.triage.domain.ObserveSnoozedUseCase
import com.codingpit.muviss.feature.triage.domain.UnsnoozeUseCase
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class SnoozedUiState(
    val loading: Boolean = true,
    val titles: List<SnoozedTitle> = emptyList(),
)

/**
 * The way back from a mis-snooze once the undo snackbar is gone — the same
 * argument ADR 0010 made for the Skipped screen, and the reason a Snooze is
 * not fire-and-forget: a three-month postponement made by accident would
 * otherwise be unrecoverable.
 *
 * Unsnoozing only soft-deletes the Snooze. A Snooze saved nothing and ticked
 * nothing, so unlike the deck's undo of a saving verdict there is no
 * collection row or progress to unwind.
 */
class SnoozedViewModel(
    observeSnoozed: ObserveSnoozedUseCase,
    private val unsnooze: UnsnoozeUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SnoozedUiState())
    val state: StateFlow<SnoozedUiState> = _state.asStateFlow()

    init {
        observeSnoozed()
            .onEach { titles -> _state.update { it.copy(loading = false, titles = titles) } }
            .launchInReporting(viewModelScope)
    }

    fun onUnsnooze(mediaId: MediaId) {
        viewModelScope.launchReporting { unsnooze(mediaId) }
    }
}
