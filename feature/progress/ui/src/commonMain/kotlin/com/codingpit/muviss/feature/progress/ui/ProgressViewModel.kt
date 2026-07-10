@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.domain.EpisodeOrdering
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One currently-Watching show, with the next episode the user hasn't seen. */
data class WatchNextItem(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val nextEpisode: Episode?,
)

data class ProgressUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val items: List<WatchNextItem> = emptyList(),
    val error: String? = null,
)

/**
 * Drives the Progress ("Watch next") tab: every currently-[WatchStatus.WATCHING]
 * show from [collectionApi] (collection's `:api` — the cross-feature contract,
 * per ADR 0004), paired with the next episode the user hasn't ticked yet.
 * Season/episode structure is fetched once per show and cached; seen state is
 * fully reactive so ticking (here or in Detail) immediately advances the item.
 */
class ProgressViewModel(
    private val collectionApi: CollectionApi,
    private val observeSeenEpisodes: ObserveSeenEpisodesUseCase,
    private val toggleEpisodeSeen: ToggleEpisodeSeenUseCase,
    private val fetchEpisodeCatalog: FetchEpisodeCatalogUseCase,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(ProgressUiState())
    val state: StateFlow<ProgressUiState> = _state.asStateFlow()

    private val catalogs = MutableStateFlow<Map<MediaId, List<Season>>>(emptyMap())

    init {
        collectionApi.observeSummaries()
            .map { summaries -> summaries.filter { it.status == WatchStatus.WATCHING } }
            .onEach { watching -> loadMissingCatalogs(watching.map { it.mediaId }) }
            .flatMapLatest { watching -> watchNextItems(watching) }
            .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } }
            .onEach { items -> _state.update { it.copy(loading = false, items = items, error = null) } }
            .launchIn(viewModelScope)
    }

    /** Ticks [item]'s next episode seen, advancing the item to the following one. */
    fun tickNext(item: WatchNextItem) {
        val next = item.nextEpisode ?: return
        viewModelScope.launch { toggleEpisodeSeen(next.id, true) }
    }

    /** Re-fetches every watching show's episode catalog (picks up newly aired episodes); the pull-to-refresh action. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val ids = catalogs.value.keys.toList()
            ids.forEach { id -> fetchEpisodeCatalog(id).onSuccess { seasons -> catalogs.update { it + (id to seasons) } } }
            _state.update { it.copy(refreshing = false) }
        }
    }

    private fun watchNextItems(watching: List<CollectionSummary>): Flow<List<WatchNextItem>> = when {
        watching.isEmpty() -> flowOf(emptyList())
        else -> combine(watching.map(::watchNextItemFlow)) { it.toList() }
    }

    private fun watchNextItemFlow(summary: CollectionSummary): Flow<WatchNextItem> = combine(
        observeSeenEpisodes(summary.mediaId),
        catalogs,
    ) { seen, catalogMap ->
        val seasons = catalogMap[summary.mediaId].orEmpty()
        val next = EpisodeOrdering.nextUnseen(seasons, seen, clock.todayEpochDay())
        WatchNextItem(summary.mediaId, summary.title, summary.posterUrl, next)
    }

    private fun loadMissingCatalogs(mediaIds: List<MediaId>) {
        val missing = mediaIds.filterNot { catalogs.value.containsKey(it) }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            missing.forEach { id ->
                fetchEpisodeCatalog(id).onSuccess { seasons -> catalogs.update { it + (id to seasons) } }
            }
        }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
