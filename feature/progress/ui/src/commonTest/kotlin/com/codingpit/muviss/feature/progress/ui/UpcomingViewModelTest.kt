@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.core.testing.FakeClock
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
import com.codingpit.muviss.models.MetadataError
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

internal class FakeUpcomingCollectionApi(summaries: List<CollectionSummary>, var failure: Throwable? = null) : CollectionApi {
    val flow = MutableStateFlow(summaries)
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
    override fun observeSummaries(): Flow<List<CollectionSummary>> = failure?.let { kotlinx.coroutines.flow.flow { throw it } } ?: flow
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

internal class FakeUpcomingCatalogSource(private val bySeasons: Map<MediaId, List<Season>>) : EpisodeCatalogSource {
    var failure: Throwable? = null

    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> = failure?.let { Result.failure(it) } ?: Result.success(bySeasons[mediaId].orEmpty())
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
        EpisodeCatalogCache(FetchEpisodeCatalogUseCase(catalogSource), InMemoryEpisodeCatalogStore()),
        FakeClock(today),
    )

    @Test
    fun a_metadata_error_shows_its_mapped_copy() = runTest {
        val vm = viewModel(
            FakeUpcomingCollectionApi(emptyList(), failure = MetadataError.RateLimited(retryAfterSeconds = 12)),
            FakeUpcomingCatalogSource(emptyMap()),
        )
        advanceUntilIdle()

        assertEquals(MetadataError.RateLimited().userMessage, vm.state.value.error.text())
    }

    @Test
    fun a_raw_exception_never_reaches_the_screen() = runTest {
        val leaky = IllegalStateException("Unable to resolve host api.themoviedb.org?api_key=SECRET")
        val vm = viewModel(FakeUpcomingCollectionApi(emptyList(), failure = leaky), FakeUpcomingCatalogSource(emptyMap()))
        advanceUntilIdle()

        assertEquals("Something went wrong", vm.state.value.error.text())
    }

    @Test
    fun lists_future_episodes_of_a_saved_tv_show() = runTest {
        val seasons = listOf(Season(1, "Season 1", listOf(episode(1, 1, airDay = 100), episode(1, 2, airDay = 107))))
        val vm = viewModel(FakeUpcomingCollectionApi(listOf(summary(tvShow))), FakeUpcomingCatalogSource(mapOf(tvShow to seasons)))
        advanceUntilIdle()

        val buckets = vm.state.value.groups.map { it.bucket }
        assertEquals(listOf(UpcomingBucket.TODAY, UpcomingBucket.LATER), buckets)
        assertEquals(com.codingpit.muviss.feature.progress.domain.UpcomingDate.Today, vm.state.value.groups.first { it.bucket == UpcomingBucket.TODAY }.rows.single().date)
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
            EpisodeCatalogCache(FetchEpisodeCatalogUseCase(updatedSource), InMemoryEpisodeCatalogStore()),
            FakeClock(today),
        )
        vmWithUpdatedSource.refresh()
        advanceUntilIdle()

        assertEquals(1, vmWithUpdatedSource.state.value.groups.sumOf { it.rows.size })
    }

    @Test
    fun a_refresh_that_cannot_fetch_says_so_and_still_stops_the_spinner() = runTest {
        val source = FakeUpcomingCatalogSource(mapOf(tvShow to emptyList()))
        val vm = viewModel(FakeUpcomingCollectionApi(listOf(summary(tvShow))), source)
        advanceUntilIdle()

        source.failure = MetadataError.Offline()
        vm.refresh()
        advanceUntilIdle()

        assertEquals(false, vm.state.value.refreshing)
        assertEquals(MetadataError.Offline().userMessage, vm.state.value.message.text())
        vm.consumeMessage()
        assertEquals(null, vm.state.value.message.text())
    }

    @Test
    fun retry_after_a_failed_pipeline_resubscribes() = runTest {
        val api = FakeUpcomingCollectionApi(listOf(summary(tvShow)), failure = MetadataError.Offline())
        val vm = viewModel(api, FakeUpcomingCatalogSource(mapOf(tvShow to emptyList())))
        advanceUntilIdle()
        assertEquals(MetadataError.Offline().userMessage, vm.state.value.error.text())

        api.failure = null
        vm.retry()
        advanceUntilIdle()

        assertEquals(null, vm.state.value.error.text())
    }
}

/** What the user would read, in the test JVM's pinned en-US. */
private suspend fun UiText?.text(): String? = this?.resolveAsync()
