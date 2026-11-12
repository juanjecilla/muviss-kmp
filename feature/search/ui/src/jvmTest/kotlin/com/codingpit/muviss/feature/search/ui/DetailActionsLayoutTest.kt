@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.ProductionStatus
import com.codingpit.muviss.models.Season
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The icon row under the poster on the expanded layout (>= 840 dp), where it sits in a 150 dp column. A saved
 * title shows four 48 dp targets, which cannot fit that column on one line, so the row has to wrap and each
 * target must stay inside the row's own bounds. The compact layout is covered by the Detail goldens.
 */
class DetailActionsLayoutTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_saved_title_icon_row_keeps_all_four_targets_inside_the_poster_column() = runComposeUiTest {
        setUpExpanded(width = 900.dp, height = 700.dp)

        val row = onNodeWithTag(DETAIL_ACTIONS_TAG).fetchSemanticsNode().boundsInRoot
        onNodeWithTag(DETAIL_ACTIONS_TAG).onChildren().assertCountEquals(4)
        onNodeWithTag(DETAIL_ACTIONS_TAG).onChildren().fetchSemanticsNodes().forEach { target ->
            val bounds = target.boundsInRoot
            assertTrue(bounds.width > 0f && bounds.height > 0f, "target was not placed: $bounds")
            assertTrue(bounds.right <= row.right + 0.5f, "target ends at ${bounds.right}, row ends at ${row.right}")
            assertTrue(bounds.bottom <= row.bottom + 0.5f, "target ends at ${bounds.bottom}, row ends at ${row.bottom}")
        }
    }

    private fun ComposeUiTest.setUpExpanded(width: Dp, height: Dp) {
        val viewModel = showViewModel()
        setContent {
            ExpandedUnderTest(viewModel, width, height)
        }
        mainClock.autoAdvance = false
        waitForIdle()
    }

    @Composable
    private fun ExpandedUnderTest(viewModel: DetailViewModel, width: Dp, height: Dp) {
        MuvissTheme(darkTheme = false) {
            GoldenSurface(width = width, height = height) {
                DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenEpisode = {})
            }
        }
    }
}

private val actionsShow = MediaId.tmdbTv("5150")

private class ActionsDayClock : AppClock {
    override fun nowEpochMs(): Long = 20_000L * 86_400_000L
}

private val actionsDetails = MediaDetails(
    summary = MediaSummary(id = actionsShow, title = "Layout Show", year = 2024, overview = "A show.", rating = 7.0),
    genres = listOf("Drama"),
    runtimeMinutes = 45,
    productionStatus = ProductionStatus.RETURNING,
    seasons = listOf(
        Season(
            number = 1,
            name = "Season 1",
            episodes = listOf(Episode(EpisodeId(actionsShow, 1, 1), 1, 1, "Pilot", airDateEpochDay = 19_001L, runtimeMinutes = 45)),
        ),
    ),
)

private fun showViewModel(): DetailViewModel {
    val repo = FakeDetailRepo(details = actionsDetails)
    val collection = FakeCollectionApi().apply { runBlocking { add(actionsDetails) } }
    return DetailViewModel(
        actionsShow,
        MediaDetailUseCase(repo),
        DetailPeers(collection, FakeProgressApi(), FakeTriageApi(), FakeMutingApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        ActionsDayClock(),
    )
}
