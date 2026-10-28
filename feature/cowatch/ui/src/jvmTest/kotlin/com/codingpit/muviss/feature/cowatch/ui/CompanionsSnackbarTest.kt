@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.cowatch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.cowatch.api.CompanionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The "Code copied" snackbar floats over the bottom of Companions' scrolling
 * column when the platform does not confirm a copy itself (#568). Scrolled to
 * its end, the last row must sit clear of it, as Upcoming and Lists do (#474, #475).
 */
class CompanionsSnackbarTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_copied_snackbar_does_not_cover_the_last_row_when_scrolled_to_its_end() = runComposeUiTest {
        val api = FakeCoWatchApi(
            companions = listOf(companion("pal", CompanionState.ACTIVE), companion("sam", CompanionState.AWAITING_CONFIRMATION)),
        ).apply { createInviteResult = Result.success("MUVISS-7742") }
        // canShare = false leaves Copy as the only way out, and confirmsCopy is false, so the screen says so itself.
        setContent { CompanionsUnderTest(CompanionsViewModel(api), FakeTextSharer(canShare = false)) }
        // Hand-driven clock: auto-advance would run the snackbar's four-second timeout while the test waits.
        mainClock.autoAdvance = false
        waitForIdle()

        onNodeWithText("Create a code").performClick()
        mainClock.advanceTimeBy(100)
        onNodeWithText("Copy").performClick()
        mainClock.advanceTimeBy(500)
        onNodeWithTag(COMPANIONS_SNACKBAR_TAG).assertExists()

        assertLastRowClearsSnackbar()
    }

    /**
     * Scrolls [COMPANIONS_LIST_TAG] to its end, then compares the last row with the
     * snackbar. The precondition keeps the test honest: a last row already on screen
     * before scrolling would pass without proving anything about the bottom padding.
     */
    private fun ComposeUiTest.assertLastRowClearsSnackbar() {
        val listBottom = onNodeWithTag(COMPANIONS_LIST_TAG).getUnclippedBoundsInRoot().bottom
        val rowBottomBeforeScrolling = onNodeWithText(LAST_ROW_TEXT).getUnclippedBoundsInRoot().bottom
        assertTrue(rowBottomBeforeScrolling > listBottom, "the window must be too short for the last row to start on screen")

        repeat(SCROLL_SWIPES) { onNodeWithTag(COMPANIONS_LIST_TAG).performTouchInput { swipeUp() } }
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(COMPANIONS_SNACKBAR_TAG).getUnclippedBoundsInRoot().top
        assertTrue(snackbarTop < listBottom, "the snackbar must be on screen to be covering anything (top $snackbarTop, window $listBottom)")
        val lastRowBottom = onNodeWithText(LAST_ROW_TEXT).getUnclippedBoundsInRoot().bottom
        assertTrue(lastRowBottom <= snackbarTop, "last row ends at $lastRowBottom, snackbar starts at $snackbarTop")
    }

    @Composable
    private fun CompanionsUnderTest(viewModel: CompanionsViewModel, sharer: FakeTextSharer) {
        MuvissTheme(darkTheme = false) {
            Box(Modifier.size(WINDOW_WIDTH, WINDOW_HEIGHT)) {
                CompanionsScreen(viewModel, onBack = {}, onOpenShortlist = {}, textSharer = sharer)
            }
        }
    }

    private companion object {
        /** Phone-width column, short enough that the two companion cards and the invite push the switch below the fold. */
        val WINDOW_WIDTH = 412.dp
        val WINDOW_HEIGHT = 600.dp

        const val SCROLL_SWIPES = 12

        /** The switch row's description: the last text on the screen, so its bottom is the row's bottom. */
        const val LAST_ROW_TEXT = "Titles you've already watched can be suggested for a rewatch, unless you've said otherwise for one."
    }
}
