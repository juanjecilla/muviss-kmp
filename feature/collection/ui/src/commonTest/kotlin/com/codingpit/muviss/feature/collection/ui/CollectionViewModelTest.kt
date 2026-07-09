@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.ui

import app.cash.turbine.test
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.CollectionRepository
import com.codingpit.muviss.feature.collection.domain.MediaSnapshotSource
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
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

private fun entry(
    id: MediaId,
    favorite: Boolean = false,
    seenEpisodes: Int = 0,
    airedEpisodes: Int = 1,
    productionStatus: ProductionStatus = ProductionStatus.RELEASED,
) = CollectionEntry(
    mediaId = id,
    title = id.toString(),
    posterUrl = null,
    releaseYear = null,
    productionStatus = productionStatus,
    totalEpisodes = airedEpisodes,
    airedEpisodes = airedEpisodes,
    favorite = favorite,
    addedAtEpochMs = 0L,
    seenEpisodes = seenEpisodes,
)

private class FakeCollectionRepository(entries: List<CollectionEntry>) : CollectionRepository {
    private val flow = MutableStateFlow(entries)
    val setFavoriteCalls = mutableListOf<Pair<MediaId, Boolean>>()

    override fun observeAll(): Flow<List<CollectionEntry>> = flow
    override fun observeEntry(mediaId: MediaId): Flow<CollectionEntry?> = error("not used")
    override suspend fun upsertSnapshot(details: MediaDetails) = error("not used")
    override suspend fun remove(mediaId: MediaId) = error("not used")
    override suspend fun setFavorite(mediaId: MediaId, favorite: Boolean) {
        setFavoriteCalls += mediaId to favorite
    }
}

private class NoopSnapshotSource : MediaSnapshotSource {
    override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = Result.success(MediaDetails(MediaSummary(mediaId, "x")))
}

class CollectionViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val notStarted = entry(MediaId.tmdbMovie("1"))
    private val watching = entry(MediaId.tmdbTv("2"), seenEpisodes = 1, airedEpisodes = 5)
    private val favoriteButNotStarted = entry(MediaId.tmdbMovie("3"), favorite = true)

    private fun viewModel(repository: FakeCollectionRepository) = CollectionViewModel(
        ObserveCollectionUseCase(repository),
        ToggleFavoriteUseCase(repository),
        RefreshCollectionSnapshotsUseCase(repository, NoopSnapshotSource()),
    )

    @Test
    fun defaults_to_not_started_filter_and_loads_entries() = runTest {
        val vm = viewModel(FakeCollectionRepository(listOf(notStarted, watching, favoriteButNotStarted)))
        advanceUntilIdle()

        assertEquals(CollectionFilter.NOT_STARTED, vm.state.value.filter)
        assertEquals(setOf(notStarted, favoriteButNotStarted), vm.state.value.visibleEntries.toSet())
    }

    @Test
    fun selecting_watching_filter_narrows_the_visible_list() = runTest {
        val vm = viewModel(FakeCollectionRepository(listOf(notStarted, watching)))
        advanceUntilIdle()

        vm.selectFilter(CollectionFilter.WATCHING)
        assertEquals(listOf(watching), vm.state.value.visibleEntries)
    }

    @Test
    fun favorites_filter_ignores_status() = runTest {
        val vm = viewModel(FakeCollectionRepository(listOf(notStarted, watching, favoriteButNotStarted)))
        advanceUntilIdle()

        vm.selectFilter(CollectionFilter.FAVORITES)
        assertEquals(listOf(favoriteButNotStarted), vm.state.value.visibleEntries)
    }

    @Test
    fun setFavorite_delegates_to_the_repository() = runTest {
        val repository = FakeCollectionRepository(listOf(notStarted))
        val vm = viewModel(repository)
        advanceUntilIdle()

        vm.setFavorite(notStarted.mediaId, true)
        advanceUntilIdle()

        assertEquals(listOf(notStarted.mediaId to true), repository.setFavoriteCalls)
    }

    @Test
    fun loading_flips_off_once_the_library_emits() = runTest {
        val vm = viewModel(FakeCollectionRepository(emptyList()))
        vm.state.test {
            var current = awaitItem()
            while (current.loading) current = awaitItem()
            assertTrue(current.entries.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
