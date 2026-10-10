@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.collection.domain.CollectionEntry
import com.codingpit.muviss.feature.collection.domain.CollectionRefreshThrottle
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.ProductionStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * What the Library — the app's first tab — looks like with something in it,
 * in both themes. One title per derived status, plus a favourite, so the
 * status treatments on the grid are all on screen at once and a token change
 * to any of them shows.
 *
 * JVM-only: golden capture needs Skia. Posters never load in a test and
 * `PosterImage` falls back to drawing the title, so these are deterministic
 * without a network. `darkTheme` is passed explicitly: `MuvissTheme` otherwise
 * follows the host machine's setting.
 *
 * The default tolerance, deliberately: Ubuntu's heavier glyphs have not been
 * measured against this frame yet, and a guessed allowance would hide as much
 * as it absorbs. If CI fails it reports the measured share, and that number,
 * with headroom, belongs in a documented constant like `DECK_TOLERANCE`.
 */
class CollectionGoldenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_library_shows_every_status_on_its_grid() = runComposeUiTest {
        setContent { LibraryUnderTest(darkTheme = false) }
        waitForIdle()
        assertMatchesGolden("collection-library")
    }

    @Test
    fun a_library_shows_every_status_on_its_grid_dark() = runComposeUiTest {
        setContent { LibraryUnderTest(darkTheme = true) }
        waitForIdle()
        assertMatchesGolden("collection-library-dark")
    }
}

private class FixedClock : AppClock {
    override fun nowEpochMs(): Long = 0L
}

@Composable
private fun LibraryUnderTest(darkTheme: Boolean) {
    val repository = FakeCollectionRepository(library)
    val viewModel = CollectionViewModel(
        ObserveCollectionUseCase(repository),
        ToggleFavoriteUseCase(repository),
        RefreshCollectionSnapshotsUseCase(repository, NoopSnapshotSource()),
        // Pre-claims the automatic refresh, so no "refreshing" state is
        // captured half way through.
        CollectionRefreshThrottle(FixedClock()).apply { recordRefresh() },
    )
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface {
            CollectionScreen(viewModel, ListsViewModel(ListsUseCases(FakeListsRepository())), onOpenDetail = {}, onOpenList = {})
        }
    }
}

private fun show(id: String, title: String, seen: Int, aired: Int, status: ProductionStatus) = CollectionEntry(
    mediaId = MediaId.tmdbTv(id),
    title = title,
    posterUrl = null,
    releaseYear = null,
    productionStatus = status,
    totalEpisodes = aired,
    airedEpisodes = aired,
    favorite = false,
    addedAtEpochMs = 0L,
    seenEpisodes = seen,
)

private fun movie(id: String, title: String, watched: Boolean) = CollectionEntry(
    mediaId = MediaId.tmdbMovie(id),
    title = title,
    posterUrl = null,
    releaseYear = null,
    productionStatus = ProductionStatus.RELEASED,
    totalEpisodes = 1,
    airedEpisodes = 1,
    favorite = false,
    addedAtEpochMs = 0L,
    seenEpisodes = if (watched) 1 else 0,
)

/** Newest first: each entry's `addedAtEpochMs` follows its position, so the default sort is stable. */
private val library = listOf(
    show("1396", "Breaking Bad", seen = 62, aired = 62, status = ProductionStatus.ENDED).copy(favorite = true, releaseYear = 2008),
    show("100088", "The Last of Us", seen = 4, aired = 16, status = ProductionStatus.RETURNING).copy(releaseYear = 2023),
    show("95396", "Severance", seen = 19, aired = 19, status = ProductionStatus.RETURNING).copy(releaseYear = 2022),
    show("1399", "Game of Thrones", seen = 0, aired = 73, status = ProductionStatus.ENDED).copy(releaseYear = 2011),
    movie("603", "The Matrix", watched = true).copy(releaseYear = 1999),
    movie("329865", "Arrival", watched = false).copy(releaseYear = 2016),
).let { entries -> entries.mapIndexed { index, entry -> entry.copy(addedAtEpochMs = (entries.size - index).toLong()) } }
