package com.codingpit.muviss.feature.progress.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.feature.progress.domain.WatchNextUseCase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.toUserMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class ProgressUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val items: List<WatchNextItem> = emptyList(),
    val error: String? = null,
)

/**
 * Drives the Progress tab's Watch Next segment.
 *
 * The collection x catalog x ticks join this used to perform now lives in
 * [WatchNextUseCase], and [WatchNextItem] with it — the Android and iOS
 * widgets ask the same question from outside Compose entirely (EPIC 22), and
 * the screen is one caller of that answer rather than the place it is
 * computed. What is left here is genuinely screen-shaped: the loading and
 * refreshing flags, and turning a tap into a write.
 */
class ProgressViewModel(
    private val watchNext: WatchNextUseCase,
    private val toggleEpisodeSeen: ToggleEpisodeSeenUseCase,
    private val catalogCache: EpisodeCatalogCache,
) : ViewModel() {

    private val _state = MutableStateFlow(ProgressUiState())
    val state: StateFlow<ProgressUiState> = _state.asStateFlow()

    init {
        watchNext()
            .catch { e -> _state.update { it.copy(loading = false, error = e.toUserMessage(DEFAULT_ERROR)) } }
            .onEach { items -> _state.update { it.copy(loading = false, items = items, error = null) } }
            .launchInReporting(viewModelScope)
    }

    /** Ticks [item]'s next episode seen, advancing the item to the following one. */
    fun tickNext(item: WatchNextItem) {
        val next = item.nextEpisode ?: return
        viewModelScope.launchReporting { toggleEpisodeSeen(next.id, true) }
    }

    /** Reverts a tick — the undo-snackbar action; the reactive pipeline re-surfaces the episode. */
    fun untick(episodeId: EpisodeId) {
        viewModelScope.launchReporting { toggleEpisodeSeen(episodeId, false) }
    }

    /** Re-fetches every cached show's episode catalog (picks up newly aired episodes); the pull-to-refresh action. */
    fun refresh() {
        viewModelScope.launchReporting {
            _state.update { it.copy(refreshing = true) }
            catalogCache.refresh()
            _state.update { it.copy(refreshing = false) }
        }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
