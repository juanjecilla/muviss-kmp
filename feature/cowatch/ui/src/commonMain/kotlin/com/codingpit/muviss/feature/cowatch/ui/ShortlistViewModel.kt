package com.codingpit.muviss.feature.cowatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.cowatch.api.CoWatchApi
import com.codingpit.muviss.feature.cowatch.api.ShortlistItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class ShortlistUiState(
    val loading: Boolean = true,
    val items: List<ShortlistItem> = emptyList(),
    /**
     * When the Companion's pool was last published, or null if none has ever
     * arrived. Rendered rather than hidden: only their device can publish it,
     * so staleness here is a ceiling this app cannot raise (ADR 0022, #121).
     */
    val companionPoolPublishedAtEpochMs: Long? = null,
)

class ShortlistViewModel(
    private val coWatch: CoWatchApi,
) : ViewModel() {

    private val _state = MutableStateFlow(ShortlistUiState())
    val state: StateFlow<ShortlistUiState> = _state.asStateFlow()

    private var observing: String? = null

    /**
     * Started from the screen rather than the constructor because the Companion
     * is a navigation argument. Guarded so recomposition does not stack a second
     * collector on the same flow.
     */
    fun start(companionUserId: String) {
        if (observing == companionUserId) return
        observing = companionUserId
        // Publishing our own half first: a Shortlist is an intersection, so a
        // pool that never went out makes it permanently empty and looks like
        // the feature not working.
        viewModelScope.launchReporting { coWatch.refreshPublishedPool() }
        coWatch.observeShortlist(companionUserId)
            .onEach { shortlist ->
                _state.update {
                    it.copy(
                        loading = false,
                        items = shortlist.items,
                        companionPoolPublishedAtEpochMs = shortlist.companionPoolPublishedAtEpochMs,
                    )
                }
            }
            .launchInReporting(viewModelScope)
    }
}
