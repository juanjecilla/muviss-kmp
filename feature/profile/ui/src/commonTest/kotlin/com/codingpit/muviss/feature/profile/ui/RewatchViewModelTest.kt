@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.common.epochDayOfCivil
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.profile.domain.ObserveRewatchStatsUseCase
import com.codingpit.muviss.feature.profile.domain.RewatchWindow
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.api.ProgressApi
import com.codingpit.muviss.feature.progress.api.WatchNextItem
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RewatchCollectionApi : CollectionApi {
    val summaries = MutableStateFlow<List<CollectionSummary>>(emptyList())
    var failure: Throwable? = null

    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
    override fun observeSummaries(): Flow<List<CollectionSummary>> = failure?.let { flow { throw it } } ?: summaries
    override suspend fun add(details: MediaDetails) = error("not used")
    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
    override suspend fun setRating(mediaId: MediaId, rating: Int?) = error("not used")
    override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")

    override suspend fun setRevisitWillingness(mediaId: MediaId, willing: Boolean?) = error("not used")

    override suspend fun setCoWatchPinned(mediaId: MediaId, pinned: Boolean) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}

/**
 * Records the `since` bound it was asked for, which is the only observable
 * difference the window makes — the ranking is narrowed by the query, never
 * in memory (ADR 0012).
 */
private class RewatchProgressApi : ProgressApi {
    var countsForAllTime: Map<MediaId, Int> = emptyMap()
    var countsForThisYear: Map<MediaId, Int> = emptyMap()
    val requestedBounds = mutableListOf<Long>()
    var timestamps: List<Long> = emptyList()

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> {
        requestedBounds += sinceEpochMs
        return flowOf(if (sinceEpochMs == 0L) countsForAllTime else countsForThisYear)
    }

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = flowOf(timestamps)

    override fun observeSeenEpisodes(mediaId: MediaId): Flow<Set<EpisodeId>> = error("not used")

    // Watch-next (EPIC 22) — this fake's subject never asks for it.
    override fun observeWatchNext(): Flow<List<WatchNextItem>> = flowOf(emptyList())
    override suspend fun refreshWatchNextCatalogs() = Unit

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = error("not used")
    override suspend fun setEpisodeSeen(episodeId: EpisodeId, seen: Boolean) = error("not used")
    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = flowOf(emptyMap())
    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = flowOf(emptyList())
    override suspend fun recordPlay(episodeId: EpisodeId) = error("not used")
    override suspend fun removeLatestPlay(episodeId: EpisodeId) = error("not used")
    override suspend fun clearPlays(episodeId: EpisodeId) = error("not used")
    override suspend fun markSeasonAiredSeen(season: Season, todayEpochDay: Long): List<EpisodeId> = error("not used")
    override suspend fun markShowAiredSeen(seasons: List<Season>, todayEpochDay: Long): List<EpisodeId> = error("not used")
    override suspend fun unmarkSeason(season: Season) = error("not used")
    override suspend fun unmarkShow(seasons: List<Season>) = error("not used")
    override suspend fun undoBulkMark(episodeIds: List<EpisodeId>) = error("not used")
    override suspend fun markPreviousSeen(seasons: List<Season>, target: EpisodeId) = error("not used")
    override suspend fun markAllAiredSeen(seasons: List<Season>, todayEpochDay: Long) = error("not used")
    override suspend fun clearProgress(mediaId: MediaId) = error("not used")
    override suspend fun setMovieWatched(mediaId: MediaId, watched: Boolean) = error("not used")
}

private class RewatchClock(private val epochMs: Long) : AppClock {
    override fun nowEpochMs(): Long = epochMs
}

class RewatchViewModelTest {

    private val office = MediaId.tmdbTv("2316")
    private val poorThings = MediaId.tmdbMovie("792307")

    private lateinit var collectionApi: RewatchCollectionApi
    private lateinit var progressApi: RewatchProgressApi
    private lateinit var viewModel: RewatchViewModel

    private val today = epochDayOfCivil(2026, 8, 29)
    private val startOfYear = epochMsAtStartOfDay(epochDayOfCivil(2026, 1, 1))

    private fun summary(id: MediaId, title: String) = CollectionSummary(
        mediaId = id,
        title = title,
        posterUrl = null,
        status = WatchStatus.WATCHING,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        collectionApi = RewatchCollectionApi()
        progressApi = RewatchProgressApi()
        collectionApi.summaries.value = listOf(summary(office, "The Office"), summary(poorThings, "Poor Things"))
        progressApi.countsForAllTime = mapOf(office to 41, poorThings to 5)
        progressApi.countsForThisYear = mapOf(office to 4)
        viewModel = RewatchViewModel(
            ObserveRewatchStatsUseCase(collectionApi, progressApi, RewatchClock(epochMsAtStartOfDay(today))),
        )
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun starts_on_all_time_and_splits_shows_from_movies() = runTest {
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(RewatchWindow.ALL_TIME, state.window)
        assertEquals(listOf("The Office" to 41), state.stats.ranking.shows.map { it.title to it.rewatches })
        assertEquals(listOf("Poor Things" to 5), state.stats.ranking.movies.map { it.title to it.rewatches })
        assertEquals(listOf(0L), progressApi.requestedBounds)
    }

    @Test
    fun choosing_this_year_re_queries_from_the_first_of_january() = runTest {
        advanceUntilIdle()
        viewModel.onWindowSelected(RewatchWindow.THIS_YEAR)
        advanceUntilIdle()

        assertEquals(RewatchWindow.THIS_YEAR, viewModel.state.value.window)
        assertEquals(listOf("The Office" to 4), viewModel.state.value.stats.ranking.shows.map { it.title to it.rewatches })
        assertEquals(listOf(0L, startOfYear), progressApi.requestedBounds)
    }

    @Test
    fun going_back_to_all_time_restores_the_wider_bound() = runTest {
        advanceUntilIdle()
        viewModel.onWindowSelected(RewatchWindow.THIS_YEAR)
        advanceUntilIdle()
        viewModel.onWindowSelected(RewatchWindow.ALL_TIME)
        advanceUntilIdle()

        assertEquals(listOf(0L, startOfYear, 0L), progressApi.requestedBounds)
        assertEquals(41, viewModel.state.value.stats.ranking.shows.single().rewatches)
    }

    /** The trend keeps its own window, so narrowing the ranking must not narrow the chart. */
    @Test
    fun the_trend_always_covers_twelve_months_whatever_the_window() = runTest {
        progressApi.timestamps = listOf(epochMsAtStartOfDay(epochDayOfCivil(2026, 3, 2)))
        viewModel = RewatchViewModel(
            ObserveRewatchStatsUseCase(collectionApi, progressApi, RewatchClock(epochMsAtStartOfDay(today))),
        )
        advanceUntilIdle()
        val allTime = viewModel.state.value.stats.monthly

        viewModel.onWindowSelected(RewatchWindow.THIS_YEAR)
        advanceUntilIdle()

        assertEquals(12, allTime.size)
        assertEquals(1, allTime.single { it.year == 2026 && it.month == 3 }.rewatches)
        assertEquals(allTime, viewModel.state.value.stats.monthly)
    }

    @Test
    fun an_empty_history_is_not_an_error() = runTest {
        progressApi.countsForAllTime = emptyMap()
        viewModel = RewatchViewModel(
            ObserveRewatchStatsUseCase(collectionApi, progressApi, RewatchClock(epochMsAtStartOfDay(today))),
        )
        advanceUntilIdle()

        assertTrue(viewModel.state.value.stats.ranking.isEmpty)
        assertEquals(null, viewModel.state.value.error)
        assertEquals(false, viewModel.state.value.loading)
    }

    @Test
    fun a_metadata_error_shows_its_mapped_copy() = runTest {
        collectionApi.failure = MetadataError.RateLimited(retryAfterSeconds = 12)
        viewModel = RewatchViewModel(
            ObserveRewatchStatsUseCase(collectionApi, progressApi, RewatchClock(epochMsAtStartOfDay(today))),
        )
        advanceUntilIdle()

        assertEquals(MetadataError.RateLimited().userMessage, viewModel.state.value.error)
    }

    @Test
    fun a_raw_exception_never_reaches_the_screen() = runTest {
        collectionApi.failure = IllegalStateException("Unable to resolve host api.themoviedb.org?api_key=SECRET")
        viewModel = RewatchViewModel(
            ObserveRewatchStatsUseCase(collectionApi, progressApi, RewatchClock(epochMsAtStartOfDay(today))),
        )
        advanceUntilIdle()

        assertEquals("Could not load rewatches", viewModel.state.value.error)
    }
}
