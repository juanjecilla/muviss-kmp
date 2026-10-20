@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.ui

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.collection.domain.ListsUseCases
import com.codingpit.muviss.feature.collection.domain.MediaList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Undo snackbar after deleting a list (#442) sits over the bottom of the Lists
 * column, so the last row has to scroll clear of it.
 */
class ListsSnackbarTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_undo_snackbar_does_not_cover_the_last_row_when_the_lists_are_scrolled_to_their_end() = runComposeUiTest {
        val lists = (1..20).map { MediaList("$it", "List $it", 0L, 0L) }
        val vm = ListsViewModel(ListsUseCases(FakeListsRepository(lists)))
        setContent { MuvissTheme(darkTheme = false) { ListsScreen(vm, onOpenList = {}) } }
        waitForIdle()
        mainClock.autoAdvance = false

        onNodeWithContentDescription("Delete List 1").performClick()
        mainClock.advanceTimeBy(100)
        onNodeWithText("Deleted “List 1”").assertExists()
        onNodeWithTag(LISTS_LIST_TAG).performScrollToIndex(18)
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(LISTS_SNACKBAR_TAG).fetchSemanticsNode().boundsInRoot.top
        val lastRowBottom = onAllNodesWithTag(LISTS_ROW_TAG).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue(lastRowBottom <= snackbarTop, "last row ends at $lastRowBottom, snackbar starts at $snackbarTop")
    }
}
