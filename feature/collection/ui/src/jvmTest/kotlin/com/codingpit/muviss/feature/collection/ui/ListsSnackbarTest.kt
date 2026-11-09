@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.collection.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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

    private fun ComposeUiTest.assertLastRowClearsUndoSnackbar(firstName: String, narrow: Boolean, fontScale: Float) {
        val lists = (1..20).map { MediaList("$it", if (it == 1) firstName else "List $it", 0L, 0L) }
        val vm = ListsViewModel(ListsUseCases(FakeListsRepository(lists)))
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
                MuvissTheme(darkTheme = false) {
                    // A narrow column at a large font makes the Undo message wrap to several lines.
                    Box(if (narrow) Modifier.width(NARROW_WIDTH).fillMaxHeight() else Modifier.fillMaxSize()) {
                        ListsScreen(vm, onOpenList = {})
                    }
                }
            }
        }
        waitForIdle()
        mainClock.autoAdvance = false

        // Through the view model: at 2x in a narrow column the row has no room left for its delete button.
        runOnIdle { vm.deleteList(lists.first()) }
        mainClock.advanceTimeBy(100)
        onNodeWithText("Deleted “$firstName”").assertExists()
        onNodeWithTag(LISTS_LIST_TAG).performScrollToIndex(18)
        // Past the last row into the end padding, as far as the list goes.
        repeat(3) { onNodeWithTag(LISTS_LIST_TAG).performTouchInput { swipeUp() } }
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(LISTS_SNACKBAR_TAG).fetchSemanticsNode().boundsInRoot.top
        val lastRowBottom = onAllNodesWithTag(LISTS_ROW_TAG).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue(lastRowBottom <= snackbarTop, "last row ends at $lastRowBottom, snackbar starts at $snackbarTop")
    }

    @Test
    fun the_undo_snackbar_does_not_cover_the_last_row_when_the_lists_are_scrolled_to_their_end() = runComposeUiTest {
        assertLastRowClearsUndoSnackbar(firstName = "List 1", narrow = false, fontScale = 1f)
    }

    @Test
    fun an_undo_snackbar_wrapped_by_a_long_name_and_a_large_font_still_clears_the_last_row() = runComposeUiTest {
        assertLastRowClearsUndoSnackbar(firstName = LONG_NAME, narrow = true, fontScale = 2f)
    }
}

private val NARROW_WIDTH = 220.dp
private const val LONG_NAME = "Christmas classics with the family"
