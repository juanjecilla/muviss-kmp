@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.collection.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.domain.CollectionRefreshThrottle
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.ObserveCollectionUseCase
import com.codingpit.muviss.feature.collection.domain.RefreshCollectionSnapshotsUseCase
import com.codingpit.muviss.feature.collection.domain.ToggleFavoriteUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "What's next?" on the Library (EPIC 51, #545). It is on the Library segment, above the filter chips, and is
 * drawn only when the shell hands over a callback.
 */
class LibraryNextPickEntryTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_library_offers_what_s_next_and_opening_it_calls_back() = runComposeUiTest {
        var opened = 0
        setContent { LibraryWithEntry(onOpenNextPick = { opened++ }) }
        waitForIdle()

        onNodeWithTag(LIBRARY_NEXT_PICK_TAG).assertExists().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun without_a_callback_the_library_draws_no_what_s_next() = runComposeUiTest {
        setContent { LibraryWithEntry(onOpenNextPick = null) }
        waitForIdle()

        onNodeWithTag(LIBRARY_NEXT_PICK_TAG).assertDoesNotExist()
    }
}

@Composable
private fun LibraryWithEntry(onOpenNextPick: (() -> Unit)?) {
    val repository = FakeCollectionRepository(emptyList())
    val viewModel = CollectionViewModel(
        ObserveCollectionUseCase(repository),
        ToggleFavoriteUseCase(repository),
        RefreshCollectionSnapshotsUseCase(repository, NoopSnapshotSource()),
        CollectionRefreshThrottle(FakeClock(0L)).apply { recordRefresh() },
    )
    MuvissTheme(darkTheme = false) {
        CollectionScreen(
            viewModel = viewModel,
            listsViewModel = ListsViewModel(ListsUseCases(FakeListsRepository())),
            onOpenDetail = {},
            onOpenList = {},
            onOpenNextPick = onOpenNextPick,
        )
    }
}
