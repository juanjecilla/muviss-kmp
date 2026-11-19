@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeUp
import com.codingpit.muviss.core.common.connectivity.ConnectivityMonitor
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverFeed
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.NoDiscoverCache
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.models.Genre
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
import kotlin.test.assertTrue

/**
 * Home is one [DiscoverBrowse] call site for the whole load (#723): when Discover finishes, the page
 * it was scrolled to stays where it was. A second call site (the skeleton branch and the loaded branch
 * each calling it) gave the loaded page a fresh scroll state, which jumped back to the top.
 */
class HomeScrollSurvivesLoadTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_home_scroll_position_survives_the_discover_load_completing() = runComposeUiTest {
        val release = CompletableDeferred<Unit>()
        setContent { HomeWhileDiscoverLoads(release) }
        waitForIdle()

        // Content must be taller than the viewport, or the scroll below proves nothing.
        assertTrue(verticalMaxScroll() > 0f, "Home has nothing to scroll while it loads")
        onNode(verticalScrollable()).performTouchInput { swipeUp() }
        waitForIdle()
        val scrolled = verticalOffset()
        assertTrue(scrolled > 0f, "the swipe did not scroll Home")

        release.complete(Unit)
        waitForIdle()

        assertEquals(scrolled, verticalOffset(), "Home jumped back when the load completed")
    }

    private fun ComposeUiTest.verticalMaxScroll(): Float = onNode(verticalScrollable()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()

    private fun ComposeUiTest.verticalOffset(): Float = onNode(verticalScrollable()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun verticalScrollable(): SemanticsMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
}

/** Home with a favorite to seed "For you", so the page is tall enough to scroll, and Discover held back. */
@Composable
private fun HomeWhileDiscoverLoads(release: CompletableDeferred<Unit>) {
    val favorite = CollectionSummary(
        MediaId.tmdbMovie("1"),
        "Saved title",
        posterUrl = null,
        status = WatchStatus.NOT_STARTED,
        favorite = true,
        rating = null,
        addedAtEpochMs = 0L,
    )
    // Loaded sections carry titles, as a real load does: an empty section renders nothing, which would shorten
    // the page under the scroll position and clamp it to the top for a reason that has nothing to do with the load.
    val posters = (10..15).map { MediaSummary(MediaId.tmdbMovie("$it"), "Title $it") }
    val genres = (1..8).map { Genre("$it", "Genre $it") }
    val repo = FakeRepo(
        movieGenresResult = Result.success(genres),
        tvGenresResult = Result.success(genres),
        discoverGate = { _, _ -> release.await() },
        discoverResult = { _, page, _ -> Result.success(PagedResult(posters, page, page)) },
        recommendationsResult = { Result.success(PagedResult(posters, 1, 1)) },
    )
    val viewModel = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        RecommendationsUseCase(repo),
        FakeSearchCollectionApi(listOf(favorite)),
        FakeTriageApi(),
        FakeMutingApi(),
        DiscoverFeed(repo, NoDiscoverCache, FakeClock(epochMs = 0L)),
        ConnectivityMonitor.AlwaysOnline,
    )
    MuvissTheme(darkTheme = false) {
        SearchScreen(viewModel = viewModel, onOpenDetail = {}, onOpenTriage = {})
    }
}
