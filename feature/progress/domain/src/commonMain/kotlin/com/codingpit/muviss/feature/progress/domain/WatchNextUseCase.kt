@file:OptIn(ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.domain

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * "What do I watch next": every currently-[WatchStatus.WATCHING] title from
 * [collectionApi] (collection's `:api`, the cross-feature contract per ADR
 * 0004) paired with the next episode not yet ticked.
 *
 * This join used to live in `ProgressViewModel`. It moved down when the
 * widgets arrived (EPIC 22): three surfaces now ask the same question, and a
 * question with one answer should have one implementation. The screen is now
 * one caller of this rather than its owner.
 *
 * Ordering is whatever [CollectionApi.observeSummaries] yields, filtered —
 * the widget deliberately does not sort differently from the tab it mirrors.
 */
class WatchNextUseCase(
    private val collectionApi: CollectionApi,
    private val observeSeenEpisodes: ObserveSeenEpisodesUseCase,
    private val catalogCache: EpisodeCatalogCache,
    private val clock: AppClock,
) {

    operator fun invoke(): Flow<List<WatchNextItem>> = collectionApi.observeSummaries()
        .map { summaries -> summaries.filter { it.status == WatchStatus.WATCHING } }
        .flatMapLatest { watching -> itemsWhileLoadingCatalogs(watching) }

    /**
     * Emits rows immediately from whatever catalogs are already cached, and
     * fills the gaps concurrently rather than awaiting them first.
     *
     * Awaiting would be simpler and is wrong: [EpisodeCatalogCache.loadMissing]
     * can end in a network call, so a slow or hanging provider would hold back
     * the entire list instead of one row's episode name. That is the shape of
     * the never-ending pull-to-refresh spinner EPIC 20 had to fix. The fetch
     * pushes into [EpisodeCatalogCache.catalogs], which every row is already
     * combined with, so late arrivals land on their own.
     */
    private fun itemsWhileLoadingCatalogs(watching: List<CollectionSummary>): Flow<List<WatchNextItem>> = channelFlow {
        launch { catalogCache.loadMissing(watching.map { it.mediaId }) }
        val rows = when {
            watching.isEmpty() -> flowOf(emptyList())
            else -> combine(watching.map(::itemFlow)) { it.toList() }
        }
        launch { rows.collect { send(it) } }
        awaitClose()
    }

    private fun itemFlow(summary: CollectionSummary): Flow<WatchNextItem> = combine(
        observeSeenEpisodes(summary.mediaId),
        catalogCache.catalogs,
    ) { seen, catalogMap ->
        val seasons = catalogMap[summary.mediaId].orEmpty()
        val today = clock.todayEpochDay()
        val aired = EpisodeOrdering.flatten(seasons).filter { ep -> ep.airDateEpochDay?.let { it <= today } == true }
        WatchNextItem(
            mediaId = summary.mediaId,
            title = summary.title,
            posterUrl = summary.posterUrl,
            nextEpisode = EpisodeOrdering.nextUnseen(seasons, seen, today),
            seenCount = aired.count { it.id in seen },
            airedCount = aired.size,
        )
    }
}
