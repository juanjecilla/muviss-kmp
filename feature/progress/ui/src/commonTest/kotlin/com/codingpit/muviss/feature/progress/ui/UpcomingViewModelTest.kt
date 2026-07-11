@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogSource
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.UpcomingBucket
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

private fun summary(id: MediaId, title: String = id.toString(), status: WatchStatus = WatchStatus.NOT_STARTED) = CollectionSummary(id, title, posterUrl = null, status = status)

private class FakeUpcomingCollectionApi(summaries: List<CollectionSummary>) : CollectionApi {
    val flow = MutableStateFlow(summaries)
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
    override fun observeSummaries(): Flow<List<CollectionSummary>> = flow
    override suspend fun add(details: MediaDetails) = error("not used")
    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
    override suspend fun setRating(mediaId: MediaId, rating: Int?) = error("not used")
    override suspend fun setNote(mediaId: MediaId, note: String?) = error("not used")
    override suspend fun refreshAndFindNewEpisodes(): List<NewEpisodesResult> = error("not used")
}

private class FakeUpcomingCatalogSource(private val bySeasons: Map<MediaId, List<Season>>) : EpisodeCatalogSource {
    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> = Result.success(bySeasons[mediaId].orEmpty())
}

private class FakeUpcomingClock(private val millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
}

class UpcomingViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val tvShow = MediaId.tmdbTv("1399")
    private val movie = MediaId.tmdbMovie("603")

    // "Today" is epoch day 100 (millis irrelevant beyond that division).
    private val today = 100L * 86_400_000L

    private fun episode(season: Int, number: Int, airDay: Long?) = Episode(
        id = EpisodeId(tvShow, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDay,
    )

    private fun viewModel(
        collectionApi: FakeUpcomingCollectionApi,
        catalogSource: EpisodeCatalogSource,
    ) = UpcomingViewModel(
        collectionApi,
        EpisodeCatalogCache(FetchEpisodeCatalogUseCase(catalogSource)),
        FakeUpcomingClock(today),
    )

    @Test
    fun lists_future_episodes_of_a_saved_tv_show() = runTest {
        val seasons = listOf(Season(1, "Season 1", listOf(episode(1, 1, airDay = 100), episode(1, 2, airDay = 107))))
        val vm = viewModel(FakeUpcomingCollectionApi(listOf(summary(tvShow))), FakeUpcomingCatalogSource(mapOf(tvShow to seasons)))
        advanceUntilIdle()

        val buckets = vm.state.value.groups.map { it.bucket }
        assertEquals(listOf(UpcomingBucket.TODAY, UpcomingBucket.LATER), buckets)
        assertEquals("Today", vm.state.value.groups.first { it.bucket == UpcomingBucket.TODAY }.rows.single().dateLabel)
    }

    @Test
    fun includes_shows_regardless_of_watch_status_unlike_watch_next() = runTest {
        val watched = summary(tvShow, status = WatchStatus.FINISHED)
        val seasons = listOf(Season(1, "Season 1", listOf(episode(1, 1, airDay = 100))))
        val vm = viewModel(FakeUpcomingCollectionApi(listOf(watched)), FakeUpcomingCatalogSource(mapOf(tvShow to seasons)))
        advanceUntilIdle()

        assertEquals(1, vm.state.value.groups.sumOf { it.rows.size })
    }

    @Test
    fun excludes_movies_since_they_have_no_episode_schedule() = runTest {
        val vm = viewModel(FakeUpcomingCollectionApi(listOf(summary(movie))), FakeUpcomingCatalogSource(emptyMap()))
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.isEmpty())
    }

    @Test
    fun empty_library_is_flagged_distinctly_from_nothing_upcoming() = runTest {
        val vm = viewModel(FakeUpcomingCollectionApi(emptyList()), FakeUpcomingCatalogSource(emptyMap()))
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.isEmpty())
        assertEquals(false, vm.state.value.hasLibraryEntries)
    }

    @Test
    fun nonempty_library_with_no_future_episodes_still_reports_library_has_entries() = runTest {
        val ended = summary(tvShow, status = WatchStatus.FINISHED)
        val seasons = listOf(Season(1, "Season 1", listOf(episode(1, 1, airDay = 50)))) // aired in the past
        val vm = viewModel(FakeUpcomingCollectionApi(listOf(ended)), FakeUpcomingCatalogSource(mapOf(tvShow to seasons)))
        advanceUntilIdle()

        assertTrue(vm.state.value.groups.isEmpty())
        assertEquals(true, vm.state.value.hasLibraryEntries)
    }

    @Test
    fun refresh_refetches_the_catalog_and_picks_up_newly_scheduled_episodes() = runTest {
        val source = FakeUpcomingCatalogSource(mapOf(tvShow to emptyList()))
        val vm = viewModel(FakeUpcomingCollectionApi(listOf(summary(tvShow))), source)
        advanceUntilIdle()
        assertTrue(vm.state.value.groups.isEmpty())

        val updatedSource = FakeUpcomingCatalogSource(mapOf(tvShow to listOf(Season(1, "Season 1", listOf(episode(1, 1, airDay = 100))))))
        val vmWithUpdatedSource = UpcomingViewModel(
            FakeUpcomingCollectionApi(listOf(summary(tvShow))),
            EpisodeCatalogCache(FetchEpisodeCatalogUseCase(updatedSource)),
            FakeUpcomingClock(today),
        )
        vmWithUpdatedSource.refresh()
        advanceUntilIdle()

        assertEquals(1, vmWithUpdatedSource.state.value.groups.sumOf { it.rows.size })
    }
}
