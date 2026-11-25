@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverFeed
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.NoDiscoverCache
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.PagedResult
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #965 on screen: the refresh control, Ctrl+R and the pull each reach For you, the control is off while a refresh
 * is in flight, and it is not offered over a typed query. The view model's own rules are in
 * DiscoverRefreshViewModelTest; these pin the wiring from the screen to it.
 */
class DiscoverRefreshControlTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val seed = MediaId.tmdbMovie("1")
    private val library = listOf(
        CollectionSummary(seed, "Saved", posterUrl = null, status = WatchStatus.NOT_STARTED, favorite = true, addedAtEpochMs = 0L),
    )

    /** Every For you request, and an optional hold that keeps the next one in flight. */
    private inner class Rig {
        val forYouAsks = mutableListOf<MediaId>()
        var hold: CompletableDeferred<Unit>? = null

        val repo = FakeRepo(
            recommendationsResult = { id ->
                forYouAsks += id
                hold?.await()
                Result.success(PagedResult(listOf(MediaSummary(MediaId.tmdbMovie("50"), "Recommended")), 1, 1))
            },
        )

        val viewModel = SearchViewModel(
            SearchMediaUseCase(repo),
            DiscoverMediaUseCase(repo),
            RecommendationsUseCase(repo),
            FakeSearchCollectionApi(library),
            FakeTriageApi(),
            FakeMutingApi(),
            DiscoverFeed(repo, NoDiscoverCache, FakeClock(epochMs = 1_000L)),
        )
    }

    @Test
    fun the_refresh_control_asks_tmdb_for_for_you_again() = runComposeUiTest {
        val rig = Rig()
        setContent { Screen(rig.viewModel, pullToRefresh = false) }
        waitForIdle()
        val before = rig.forYouAsks.size

        onNodeWithTag(DISCOVER_REFRESH_TAG).performClick()
        waitForIdle()

        assertEquals(before + 1, rig.forYouAsks.size)
    }

    @Test
    fun the_refresh_control_is_off_while_a_refresh_is_in_flight() = runComposeUiTest {
        val rig = Rig()
        setContent { Screen(rig.viewModel, pullToRefresh = false) }
        waitForIdle()
        val hold = CompletableDeferred<Unit>()
        rig.hold = hold

        onNodeWithTag(DISCOVER_REFRESH_TAG).performClick()
        waitForIdle()
        onNodeWithTag(DISCOVER_REFRESH_TAG).assertIsNotEnabled()

        rig.hold = null
        hold.complete(Unit)
        waitForIdle()
        onNodeWithTag(DISCOVER_REFRESH_TAG).assertIsEnabled()
    }

    @Test
    fun the_refresh_control_is_not_offered_while_a_query_is_typed() = runComposeUiTest {
        val rig = Rig()
        setContent { Screen(rig.viewModel, pullToRefresh = false) }
        waitForIdle()
        onNodeWithTag(DISCOVER_REFRESH_TAG).assertExists()

        onNode(hasSetTextAction()).performTextInput("dune")
        waitForIdle()

        onNodeWithTag(DISCOVER_REFRESH_TAG).assertDoesNotExist()
    }

    @Test
    fun ctrl_r_refreshes_where_there_is_no_pull() = runComposeUiTest {
        val rig = Rig()
        setContent { Screen(rig.viewModel, pullToRefresh = false) }
        waitForIdle()
        val before = rig.forYouAsks.size

        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.R)
            keyUp(Key.CtrlLeft)
        }
        waitForIdle()

        assertEquals(before + 1, rig.forYouAsks.size)
    }

    @Test
    fun pulling_down_refreshes_where_there_is_a_pull() = runComposeUiTest {
        val rig = Rig()
        setContent { Screen(rig.viewModel, pullToRefresh = true) }
        waitForIdle()
        val before = rig.forYouAsks.size

        // Starts below the header row, which sits above the pull, and drags well past the indicator's threshold.
        onRoot().performTouchInput { swipeDown(startY = height * 0.3f, endY = height * 0.9f, durationMillis = 400) }
        waitForIdle()

        assertEquals(before + 1, rig.forYouAsks.size)
    }

    @androidx.compose.runtime.Composable
    private fun Screen(viewModel: SearchViewModel, pullToRefresh: Boolean) {
        MuvissTheme(darkTheme = false) {
            SearchScreen(
                viewModel = viewModel,
                onOpenDetail = {},
                onOpenTriage = {},
                pullToRefresh = pullToRefresh,
            )
        }
    }
}
