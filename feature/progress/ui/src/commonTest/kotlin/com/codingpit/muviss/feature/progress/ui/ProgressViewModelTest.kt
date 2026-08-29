@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.api.EpisodePlay
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogSource
import com.codingpit.muviss.feature.progress.domain.EpisodeProgress
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.ProgressRepository
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.feature.progress.domain.WatchNextUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun summary(id: MediaId, title: String = id.toString(), status: WatchStatus = WatchStatus.WATCHING) = CollectionSummary(id, title, posterUrl = null, status = status)

private class FakeCollectionApi(summaries: List<CollectionSummary>) : CollectionApi {
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

private class FakeProgressRepository : ProgressRepository {
    private val seenByMedia = mutableMapOf<MediaId, MutableStateFlow<Set<EpisodeId>>>()
    val tickedEpisodes = mutableListOf<Pair<EpisodeId, Boolean>>()

    private fun flowFor(mediaId: MediaId) = seenByMedia.getOrPut(mediaId) { MutableStateFlow(emptySet()) }

    override fun observeForMedia(mediaId: MediaId): Flow<List<EpisodeProgress>> = flowFor(mediaId)
        .map { seen -> seen.map { EpisodeProgress(it, seen = true, updatedAtEpochMs = 0L) } }

    override fun observeSeenCount(mediaId: MediaId): Flow<Int> = flowFor(mediaId).map { it.size }

    override fun observeSeenActivityEpochDays(): Flow<Set<Long>> = error("not used")

    override suspend fun setSeen(episodeId: EpisodeId, seen: Boolean) {
        tickedEpisodes += episodeId to seen
        val flow = flowFor(episodeId.show)
        flow.value = if (seen) flow.value + episodeId else flow.value - episodeId
    }

    override suspend fun setSeenBulk(episodeIds: List<EpisodeId>, seen: Boolean) {
        episodeIds.forEach { setSeen(it, seen) }
    }

    override suspend fun clearForMedia(mediaId: MediaId) {
        flowFor(mediaId).value = emptySet()
    }

    override fun observePlayCounts(mediaId: MediaId): Flow<Map<EpisodeId, Int>> = flowFor(mediaId).map { seen -> seen.associateWith { 1 } }

    override fun observePlays(episodeId: EpisodeId): Flow<List<EpisodePlay>> = flowFor(episodeId.show).map { seen ->
        if (episodeId in seen) listOf(EpisodePlay(episodeId, 0L)) else emptyList()
    }

    override fun observeRewatchCounts(sinceEpochMs: Long): Flow<Map<MediaId, Int>> = flowOf(emptyMap())

    override fun observeRewatchTimestamps(sinceEpochMs: Long): Flow<List<Long>> = flowOf(emptyList())

    override suspend fun recordPlay(episodeId: EpisodeId) = setSeen(episodeId, true)

    override suspend fun recordPlaysForUnseen(episodeIds: List<EpisodeId>): List<EpisodeId> {
        val unseen = episodeIds.filterNot { it in flowFor(it.show).value }
        unseen.forEach { setSeen(it, true) }
        return unseen
    }

    override suspend fun removeLatestPlay(episodeId: EpisodeId) = setSeen(episodeId, false)

    override suspend fun removeLatestPlays(episodeIds: List<EpisodeId>) {
        episodeIds.filter { it in flowFor(it.show).value }.forEach { setSeen(it, false) }
    }

    override suspend fun clearPlays(episodeId: EpisodeId) = setSeen(episodeId, false)
}

private class FakeEpisodeCatalogSource(private val bySeasons: Map<MediaId, List<Season>>) : EpisodeCatalogSource {
    val refetched = mutableListOf<MediaId>()
    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> {
        refetched += mediaId
        return Result.success(bySeasons[mediaId].orEmpty())
    }
}

private class FakeClock(private val millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
}

/**
 * What is left of this suite after EPIC 22 moved the collection x catalog x
 * ticks join into `WatchNextUseCase`: the ViewModel's own behaviour — its
 * loading and refreshing flags, and turning a tap into a write. The join's
 * own rules are asserted in `WatchNextUseCaseTest`.
 */
class ProgressViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val show = MediaId.tmdbTv("1399")
    private val ep1 = EpisodeId(show, 1, 1)

    // "Today" is epoch day 100 (millis irrelevant beyond that division).
    private val today = 100L * 86_400_000L

    private fun episode(season: Int, number: Int, airDay: Long) = Episode(
        id = EpisodeId(show, season, number),
        seasonNumber = season,
        episodeNumber = number,
        name = "S${season}E$number",
        airDateEpochDay = airDay,
    )

    private val seasons = listOf(Season(1, "Season 1", listOf(episode(1, 1, 50), episode(1, 2, 60))))

    private fun viewModel(
        collectionApi: FakeCollectionApi,
        progressRepository: FakeProgressRepository,
        catalogSource: FakeEpisodeCatalogSource = FakeEpisodeCatalogSource(mapOf(show to seasons)),
    ): Pair<ProgressViewModel, EpisodeCatalogCache> {
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(catalogSource), InMemoryEpisodeCatalogStore())
        val vm = ProgressViewModel(
            WatchNextUseCase(collectionApi, ObserveSeenEpisodesUseCase(progressRepository), cache, FakeClock(today)),
            ToggleEpisodeSeenUseCase(progressRepository),
            cache,
        )
        return vm to cache
    }

    @Test
    fun renders_the_watch_next_rows_it_is_given() = runTest {
        val (vm, _) = viewModel(FakeCollectionApi(listOf(summary(show))), FakeProgressRepository())
        advanceUntilIdle()

        assertEquals(listOf(show), vm.state.value.items.map { it.mediaId })
    }

    @Test
    fun tickNext_writes_the_named_episode() = runTest {
        val repository = FakeProgressRepository()
        val (vm, _) = viewModel(FakeCollectionApi(listOf(summary(show))), repository)
        advanceUntilIdle()

        vm.tickNext(vm.state.value.items.single())
        advanceUntilIdle()

        assertEquals(listOf(ep1 to true), repository.tickedEpisodes)
    }

    @Test
    fun tickNext_on_a_row_with_no_named_episode_writes_nothing() = runTest {
        val repository = FakeProgressRepository()
        val (vm, _) = viewModel(
            FakeCollectionApi(listOf(summary(show))),
            repository,
            FakeEpisodeCatalogSource(emptyMap()),
        )
        advanceUntilIdle()

        vm.tickNext(vm.state.value.items.single())
        advanceUntilIdle()

        assertEquals(emptyList(), repository.tickedEpisodes)
    }

    @Test
    fun untick_reverts_a_tick() = runTest {
        val repository = FakeProgressRepository()
        val (vm, _) = viewModel(FakeCollectionApi(listOf(summary(show))), repository)
        advanceUntilIdle()

        vm.tickNext(vm.state.value.items.single())
        advanceUntilIdle()
        vm.untick(ep1)
        advanceUntilIdle()

        assertEquals(listOf(ep1 to true, ep1 to false), repository.tickedEpisodes)
    }

    @Test
    fun refresh_refetches_the_catalogs_and_clears_the_flag() = runTest {
        val source = FakeEpisodeCatalogSource(mapOf(show to seasons))
        val (vm, _) = viewModel(FakeCollectionApi(listOf(summary(show))), FakeProgressRepository(), source)
        advanceUntilIdle()
        val before = source.refetched.size

        vm.refresh()
        advanceUntilIdle()

        assertEquals(before + 1, source.refetched.size)
        assertEquals(false, vm.state.value.refreshing)
    }

    @Test
    fun loading_flips_off_once_summaries_emit() = runTest {
        val (vm, _) = viewModel(FakeCollectionApi(emptyList()), FakeProgressRepository())
        vm.state.test {
            var current = awaitItem()
            while (current.loading) current = awaitItem()
            assertEquals(emptyList(), current.items)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
