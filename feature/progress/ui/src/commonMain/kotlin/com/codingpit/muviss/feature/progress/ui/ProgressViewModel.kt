package com.codingpit.muviss.feature.progress.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.crash.launchInReporting
import com.codingpit.muviss.core.common.crash.launchReporting
import com.codingpit.muviss.core.common.crash.reportFailure
import com.codingpit.muviss.core.common.flags.FeatureFlags
import com.codingpit.muviss.core.common.flags.tickAnimationsActive
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

data class ProgressUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** One-shot snackbar text, e.g. a refresh that failed; cleared by `consumeMessage()`. */
    val message: UiText? = null,
    val items: List<WatchNextItem> = emptyList(),
    val error: UiText? = null,
    /** Whether a tick plays its motion: the master switch AND the tick switch (EPIC 52). The haptic ignores it. */
    val animateTicks: Boolean = true,
)

/** What a tick amounted to, once the list has settled: the screen celebrates the middle two. */
sealed interface TickOutcome {
    data object Plain : TickOutcome

    /** The tick finished [season]; there is more to watch in a later one. */
    data class SeasonFinished(val season: Int) : TickOutcome

    /** Nothing aired is left unseen: the show leaves Watch Next until a new episode airs. */
    data object CaughtUp : TickOutcome

    /** The write failed (it is reported): nothing to confirm, say or celebrate. */
    data object Failed : TickOutcome
}

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
    featureFlags: FeatureFlags,
) : ViewModel() {

    private val _state = MutableStateFlow(ProgressUiState())
    val state: StateFlow<ProgressUiState> = _state.asStateFlow()

    private var observation: Job? = null

    init {
        observe()
        featureFlags.tickAnimationsActive
            .onEach { animate -> _state.update { it.copy(animateTicks = animate) } }
            .launchInReporting(viewModelScope)
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
        tickJob(item)
    }

    /**
     * [tickNext], then waits for the list to move on and says what the tick
     * amounted to. The write runs in [viewModelScope] so leaving the screen
     * mid-wait cannot cancel it; only the wait is the caller's.
     *
     * A write that fails is [TickOutcome.Failed]; everything below assumes it
     * succeeded. A tick that makes `seen + 1 >= aired` is [TickOutcome.CaughtUp]
     * without waiting, because that row is about to be filtered out of the list. A
     * season is finished when the next episode is in a later season than the
     * one just ticked: [WatchNextItem.nextEpisode] is the first unseen aired
     * episode, so nothing earlier remains. Null when there was nothing to tick.
     */
    suspend fun tick(item: WatchNextItem): TickOutcome? {
        val ticked = item.nextEpisode ?: return null
        var saved = false
        viewModelScope.launchReporting {
            toggleEpisodeSeen(ticked.id, true)
            saved = true
        }.join()
        if (!saved) return TickOutcome.Failed
        if (item.airedCount > 0 && item.seenCount + 1 >= item.airedCount) return TickOutcome.CaughtUp
        // Timed out: the list never moved on (it is slow). Nothing to celebrate.
        val settled = withTimeoutOrNull(SETTLE_MILLIS) {
            _state.first { current -> current.items.find { it.mediaId == item.mediaId }?.nextEpisode?.id != ticked.id }
        } ?: return TickOutcome.Plain
        // Gone from the list, or nothing left to watch: either way it is caught up.
        val following = settled.items.find { it.mediaId == item.mediaId }?.nextEpisode ?: return TickOutcome.CaughtUp
        return if (following.seasonNumber > ticked.seasonNumber) TickOutcome.SeasonFinished(ticked.seasonNumber) else TickOutcome.Plain
    }

    private fun tickJob(item: WatchNextItem): Job? {
        val next = item.nextEpisode ?: return null
        return viewModelScope.launchReporting { toggleEpisodeSeen(next.id, true) }
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

/** How long [ProgressViewModel.tick] waits for the list to move on before calling a tick plain. */
private const val SETTLE_MILLIS = 1_500L
