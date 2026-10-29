@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.profile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.common.epochMsAtStartOfDay
import com.codingpit.muviss.core.designsystem.layout.ScreenInsets
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.TestSafeAreaInsets
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.profile.domain.ObserveLastSyncedAtUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileStatsUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveProfileUseCase
import com.codingpit.muviss.feature.profile.domain.ObserveSyncAccountUseCase
import com.codingpit.muviss.feature.profile.domain.ProfileActions
import com.codingpit.muviss.feature.profile.domain.SetAvatarUseCase
import com.codingpit.muviss.feature.profile.domain.SetDisplayNameUseCase
import com.codingpit.muviss.feature.profile.domain.SignInFailure
import com.codingpit.muviss.feature.profile.domain.SyncActions
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A sync message is a snackbar over the bottom of Profile's scrolling column
 * (#567): when the column is scrolled to its end, the last row must sit clear
 * of it, the way Upcoming and Lists do it (#474, #475).
 *
 * The clock is driven by hand so the snackbar is measured while it is on
 * screen, not after its exit animation has run.
 */
class ProfileSnackbarTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_sync_message_does_not_cover_the_last_row_when_scrolled_to_its_end() = runComposeUiTest {
        showSyncMessageAndScrollToEnd(message = "Sign-in was cancelled.", width = WINDOW_WIDTH, fontScale = 1f)
    }

    /** A long message in a narrow column at a large font wraps to several lines, so the snackbar is taller than one line. */
    @Test
    fun a_wrapped_sync_message_at_a_large_font_still_clears_the_last_row() = runComposeUiTest {
        showSyncMessageAndScrollToEnd(message = LONG_MESSAGE, width = NARROW_WIDTH, fontScale = 2f)
    }

    private fun ComposeUiTest.showSyncMessageAndScrollToEnd(message: String, width: Dp, fontScale: Float) {
        val syncRepository = FakeSyncRepository()
        val collectionApi = FakeCollectionApi().apply { summaries.value = listOf(watchedMovie()) }
        setContent { ProfileUnderTest(profileViewModel(collectionApi, syncRepository), width, fontScale) }
        mainClock.autoAdvance = false
        waitForIdle()

        // A sign-in failure surfaces as a sync message, which the screen shows as a snackbar.
        syncRepository.signInFailure.value = SignInFailure.Declined(message)
        waitForIdle()
        mainClock.advanceTimeBy(500)
        onNodeWithTag(PROFILE_SNACKBAR_TAG).assertExists()

        assertLastRowClearsSnackbar(lastRowTag = MOST_REWATCHED_CARD_TAG)
    }

    /**
     * Scrolls [PROFILE_LIST_TAG] to its end, then compares the last row with the
     * snackbar. The precondition keeps the test honest: if the row were already on
     * screen before scrolling, passing would prove nothing about the bottom padding.
     */
    private fun ComposeUiTest.assertLastRowClearsSnackbar(lastRowTag: String) {
        val listBottom = onNodeWithTag(PROFILE_LIST_TAG).getUnclippedBoundsInRoot().bottom
        val rowBottomBeforeScrolling = onNodeWithTag(lastRowTag).getUnclippedBoundsInRoot().bottom
        assertTrue(rowBottomBeforeScrolling > listBottom, "the window must be too short for the last row to start on screen")

        repeat(SCROLL_SWIPES) { onNodeWithTag(PROFILE_LIST_TAG).performTouchInput { swipeUp() } }
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(PROFILE_SNACKBAR_TAG).getUnclippedBoundsInRoot().top
        assertTrue(snackbarTop < listBottom, "the snackbar must be on screen to be covering anything (top $snackbarTop, window $listBottom)")
        val lastRowBottom = onNodeWithTag(lastRowTag).getUnclippedBoundsInRoot().bottom
        assertTrue(lastRowBottom <= snackbarTop, "last row ends at $lastRowBottom, snackbar starts at $snackbarTop")
    }

    @Composable
    private fun ProfileUnderTest(viewModel: ProfileViewModel, width: Dp, fontScale: Float) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = fontScale)) {
            MuvissTheme(darkTheme = false) {
                Box(Modifier.size(width, WINDOW_HEIGHT)) {
                    ScreenInsets(insets = TestSafeAreaInsets) {
                        ProfileScreen(viewModel, onOpenRewatch = {}, onOpenCompanions = null)
                    }
                }
            }
        }
    }

    private fun profileViewModel(collectionApi: FakeCollectionApi, syncRepository: FakeSyncRepository): ProfileViewModel {
        val profileRepository = FakeProfileRepository()
        val clock = FakeClock(epochMsAtStartOfDay(0))
        return ProfileViewModel(
            ObserveProfileUseCase(profileRepository),
            ObserveProfileStatsUseCase(collectionApi, FakeProgressApi(), clock),
            ProfileActions(SetDisplayNameUseCase(profileRepository), SetAvatarUseCase(profileRepository)),
            SyncActions(syncRepository, ObserveSyncAccountUseCase(syncRepository), ObserveLastSyncedAtUseCase(syncRepository)),
            clock,
        )
    }

    private fun watchedMovie() = CollectionSummary(
        mediaId = MediaId.tmdbMovie("603"),
        title = "The Matrix",
        posterUrl = null,
        status = WatchStatus.WATCHED,
        runtimeMinutes = 100,
    )

    private companion object {
        /** Phone-width column, short enough that the stats below it do not fit. */
        val WINDOW_WIDTH = 412.dp
        val NARROW_WIDTH = 220.dp
        val WINDOW_HEIGHT = 600.dp

        const val SCROLL_SWIPES = 12
        const val LONG_MESSAGE = "Sign-in did not finish because the browser was closed before the provider could send you back to Muviss."
    }
}
