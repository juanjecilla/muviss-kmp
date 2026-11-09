@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.ui

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.CollectionRefreshThrottle
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaSnapshotSource
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The refresh-failure snackbar on Collection (#442) sits over the bottom of the
 * library grid, so the last cell has to scroll clear of it.
 */
class CollectionSnackbarTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_refresh_snackbar_does_not_cover_the_last_cell_when_the_library_is_scrolled_to_its_end() = runComposeUiTest {
        val entries = (1..60).map { entry(MediaId.tmdbMovie("$it")) }
        val repository = FakeCollectionRepository(entries)
        // No pre-claimed throttle: the automatic refresh runs on init and its source throws,
        // which parks the "Couldn't refresh your library" message the snackbar shows.
        val viewModel = CollectionViewModel(
            ObserveCollectionUseCase(repository),
            ToggleFavoriteUseCase(repository),
            RefreshCollectionSnapshotsUseCase(repository, ThrowingSource()),
            CollectionRefreshThrottle(FakeClock(0L)),
        )
        setContent {
            MuvissTheme(darkTheme = false) {
                CollectionScreen(viewModel, ListsViewModel(ListsUseCases(FakeListsRepository())), onOpenDetail = {}, onOpenList = {})
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(100)
        onNodeWithText("Couldn't refresh your library").assertExists()

        onNodeWithTag(COLLECTION_GRID_TAG).performScrollToIndex(59)
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(COLLECTION_SNACKBAR_TAG).fetchSemanticsNode().boundsInRoot.top
        val lastCellBottom = onAllNodesWithTag(COLLECTION_CELL_TAG).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue(lastCellBottom <= snackbarTop, "last cell ends at $lastCellBottom, snackbar starts at $snackbarTop")
    }

    /** Fails the way a dead network does: by throwing out of the source. */
    private class ThrowingSource : MediaSnapshotSource {
        override suspend fun fetch(mediaId: MediaId): Result<MediaDetails> = error("network down")
    }

    private fun entry(id: MediaId) = CollectionEntry(
        mediaId = id,
        title = id.toString(),
        posterUrl = null,
        releaseYear = null,
        productionStatus = ProductionStatus.RELEASED,
        totalEpisodes = 1,
        airedEpisodes = 1,
        favorite = false,
        addedAtEpochMs = 0L,
        seenEpisodes = 0,
    )
}
