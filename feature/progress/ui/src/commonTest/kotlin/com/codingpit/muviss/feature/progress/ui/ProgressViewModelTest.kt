@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.collection.api.CollectionMembership
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogSource
import com.codingpit.muviss.feature.progress.domain.EpisodeProgress
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.ProgressRepository
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertNull

private fun summary(id: MediaId, title: String = id.toString(), status: WatchStatus = WatchStatus.WATCHING) = CollectionSummary(id, title, posterUrl = null, status = status)

private class FakeCollectionApi(summaries: List<CollectionSummary>) : CollectionApi {
    val flow = MutableStateFlow(summaries)
    override fun observeMembership(mediaId: MediaId): Flow<CollectionMembership?> = error("not used")
    override fun observeSummaries(): Flow<List<CollectionSummary>> = flow
    override suspend fun add(details: MediaDetails) = error("not used")
    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) = error("not used")
    override suspend fun setNotificationsMuted(mediaId: MediaId, muted: Boolean) = error("not used")
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
}

private class FakeEpisodeCatalogSource(private val bySeasons: Map<MediaId, List<Season>>) : EpisodeCatalogSource {
    override suspend fun fetch(mediaId: MediaId): Result<List<Season>> = Result.success(bySeasons[mediaId].orEmpty())
}

private class FakeClock(private val millis: Long) : AppClock {
    override fun nowEpochMs(): Long = millis
}

class ProgressViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val show = MediaId.tmdbTv("1399")
    private val ep1 = EpisodeId(show, 1, 1)
    private val ep2 = EpisodeId(show, 1, 2)

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
        catalogSource: EpisodeCatalogSource = FakeEpisodeCatalogSource(mapOf(show to seasons)),
    ) = ProgressViewModel(
        collectionApi,
        ObserveSeenEpisodesUseCase(progressRepository),
        ToggleEpisodeSeenUseCase(progressRepository),
        EpisodeCatalogCache(FetchEpisodeCatalogUseCase(catalogSource)),
        FakeClock(today),
    )

    @Test
    fun lists_next_unseen_episode_for_a_watching_show() = runTest {
        val vm = viewModel(FakeCollectionApi(listOf(summary(show))), FakeProgressRepository())
        advanceUntilIdle()

        val item = vm.state.value.items.single()
        assertEquals(show, item.mediaId)
        assertEquals(ep1, item.nextEpisode?.id)
    }

    @Test
    fun ignores_shows_that_are_not_watching() = runTest {
        val notStarted = summary(MediaId.tmdbTv("2"), status = WatchStatus.NOT_STARTED)
        val watched = summary(MediaId.tmdbTv("3"), status = WatchStatus.WATCHED)
        val vm = viewModel(FakeCollectionApi(listOf(notStarted, watched)), FakeProgressRepository())
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.value.items)
    }

    @Test
    fun tickNext_ticks_the_next_episode_and_advances_to_the_following_one() = runTest {
        val repository = FakeProgressRepository()
        val vm = viewModel(FakeCollectionApi(listOf(summary(show))), repository)
        advanceUntilIdle()

        val firstItem = vm.state.value.items.single()
        assertEquals(ep1, firstItem.nextEpisode?.id)

        vm.tickNext(firstItem)
        advanceUntilIdle()

        assertEquals(listOf(ep1 to true), repository.tickedEpisodes)
        val secondItem = vm.state.value.items.single()
        assertEquals(ep2, secondItem.nextEpisode?.id)
    }

    @Test
    fun nextEpisode_is_null_once_fully_caught_up_on_aired_episodes() = runTest {
        val repository = FakeProgressRepository()
        val vm = viewModel(FakeCollectionApi(listOf(summary(show))), repository)
        advanceUntilIdle()

        vm.tickNext(vm.state.value.items.single())
        advanceUntilIdle()
        vm.tickNext(vm.state.value.items.single())
        advanceUntilIdle()

        assertNull(vm.state.value.items.single().nextEpisode)
    }

    @Test
    fun loading_flips_off_once_summaries_emit() = runTest {
        val vm = viewModel(FakeCollectionApi(emptyList()), FakeProgressRepository())
        vm.state.test {
            var current = awaitItem()
            while (current.loading) current = awaitItem()
            assertEquals(emptyList(), current.items)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
