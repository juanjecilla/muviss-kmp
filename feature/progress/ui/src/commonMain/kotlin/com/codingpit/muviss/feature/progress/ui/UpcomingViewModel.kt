@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.UpcomingBucket
import com.codingpit.muviss.feature.progress.domain.UpcomingEpisodesCalculator
import com.codingpit.muviss.feature.progress.domain.UpcomingGroup
import com.codingpit.muviss.feature.progress.domain.UpcomingShow
import com.codingpit.muviss.feature.progress.domain.upcomingDateLabel
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One Upcoming agenda row: a future episode plus the display label ("Today", "Tomorrow", a weekday, or a date). */
data class UpcomingRow(
    val mediaId: MediaId,
    val title: String,
    val posterUrl: String?,
    val episode: Episode,
    val dateLabel: String,
)

/** One agenda section, e.g. "Today", already sorted rows. */
data class UpcomingUiGroup(
    val bucket: UpcomingBucket,
    val rows: List<UpcomingRow>,
)

data class UpcomingUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val groups: List<UpcomingUiGroup> = emptyList(),
    val error: String? = null,
    /** Whether the library has *any* saved title — distinguishes the "empty library" empty state from "nothing upcoming". */
    val hasLibraryEntries: Boolean = false,
)

/**
 * Drives the Progress tab's Upcoming segment (EPIC 14): every future-dated
 * episode across the whole library's TV shows — regardless of watch status,
 * unlike watch-next, which only tracks currently-Watching shows — grouped
 * into Today / This week / Later by [UpcomingEpisodesCalculator]. Shares
 * [catalogCache] with [ProgressViewModel] so a show's episode catalog is
 * fetched once no matter which segment the user opens first.
 */
class UpcomingViewModel(
    private val collectionApi: CollectionApi,
    private val catalogCache: EpisodeCatalogCache,
    private val clock: AppClock,
) : ViewModel() {

    private val _state = MutableStateFlow(UpcomingUiState())
    val state: StateFlow<UpcomingUiState> = _state.asStateFlow()

    init {
        collectionApi.observeSummaries()
            .onEach { all -> _state.update { it.copy(hasLibraryEntries = all.isNotEmpty()) } }
            .map { all -> all.filter { it.mediaId.type == MediaType.TV } }
            .onEach { shows -> loadMissingCatalogs(shows.map { it.mediaId }) }
            .flatMapLatest { shows -> upcomingGroups(shows) }
            .catch { e -> _state.update { it.copy(loading = false, error = e.message ?: DEFAULT_ERROR) } }
            .onEach { groups -> _state.update { it.copy(loading = false, groups = groups, error = null) } }
            .launchIn(viewModelScope)
    }

    /** Re-fetches every cached show's episode catalog (picks up newly scheduled episodes); the pull-to-refresh action. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            catalogCache.refresh()
            _state.update { it.copy(refreshing = false) }
        }
    }

    private fun upcomingGroups(shows: List<CollectionSummary>): Flow<List<UpcomingUiGroup>> = catalogCache.catalogs.map { catalogMap ->
        val today = clock.todayEpochDay()
        val upcomingShows = shows.map { UpcomingShow(it.mediaId, it.title, it.posterUrl, catalogMap[it.mediaId].orEmpty()) }
        UpcomingEpisodesCalculator.group(upcomingShows, today).map { group -> group.toUiGroup(today) }
    }

    private fun UpcomingGroup.toUiGroup(todayEpochDay: Long) = UpcomingUiGroup(
        bucket = bucket,
        rows = episodes.map { ep ->
            UpcomingRow(ep.mediaId, ep.title, ep.posterUrl, ep.episode, upcomingDateLabel(ep.airDateEpochDay, todayEpochDay))
        },
    )

    private fun loadMissingCatalogs(mediaIds: List<MediaId>) {
        viewModelScope.launch { catalogCache.loadMissing(mediaIds) }
    }

    private companion object {
        const val DEFAULT_ERROR = "Something went wrong"
    }
}
