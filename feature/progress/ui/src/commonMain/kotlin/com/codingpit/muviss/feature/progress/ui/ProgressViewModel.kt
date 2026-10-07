package com.codingpit.muviss.feature.progress.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.crash.reportFailure
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.feature.progress.domain.WatchNextUseCase
import com.codingpit.muviss.feature.progress.ui.generated.resources.Res
import com.codingpit.muviss.feature.progress.ui.generated.resources.error_generic
import com.codingpit.muviss.feature.progress.ui.generated.resources.refresh_failed
import com.codingpit.muviss.models.EpisodeId
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

data class ProgressUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** One-shot snackbar text, e.g. a refresh that failed; cleared by `consumeMessage()`. */
    val message: UiText? = null,
    val items: List<WatchNextItem> = emptyList(),
    val error: UiText? = null,
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

    private var observation: Job? = null

    init {
        observe()
    }

    private fun observe() {
        observation?.cancel()
        observation = watchNext()
            .catch { e -> _state.update { it.copy(loading = false, error = e.toUiText(UiText.Resource(Res.string.error_generic))) } }
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

    /**
     * Re-fetches every cached show's episode catalog; the pull-to-refresh
     * action. The flag clears in a `finally` (a path that leaves it true is a
     * spinner that never stops), and a refresh that could not fetch says so
     * through [ProgressUiState.message] instead of finishing silently (#73).
     */
    fun refresh() {
        viewModelScope.launchReporting {
            _state.update { it.copy(refreshing = true) }
            try {
                val failures = runCatching { catalogCache.refresh() }.reportFailure().getOrElse { listOf(it) }
                failures.firstOrNull()?.let { e -> _state.update { it.copy(message = e.toUiText(UiText.Resource(Res.string.refresh_failed))) } }
            } finally {
                _state.update { it.copy(refreshing = false) }
            }
        }
    }

    /** Acknowledges [ProgressUiState.message] once its snackbar has been shown. */
    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * The error state's Retry: subscribes again, then refreshes. Refreshing
     * alone (what Retry used to call) never restarted a pipeline that had
     * already failed, so Retry did nothing.
     */
    fun retry() {
        _state.update { it.copy(loading = true, error = null) }
        observe()
        refresh()
    }
}
