@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.runComposeUiTest
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
 * The bulk-mark undo snackbar on Detail (#442): it floats over the bottom of the
 * list, so the list's end has to clear it.
 */
class DetailSnackbarTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_undo_snackbar_does_not_cover_the_last_row_when_the_list_is_scrolled_to_its_end() = runComposeUiTest {
        val viewModel = showViewModel()
        setContent { DetailUnderTest(viewModel) }
        mainClock.autoAdvance = false
        waitForIdle()

        // "Mark whole show seen" offers the undo snackbar; it stays until its timeout.
        viewModel.markShowSeen()
        mainClock.advanceTimeBy(100)
        onNodeWithText("Undo").assertExists()

        onNodeWithTag(DETAIL_LIST_TAG).performScrollToKey("end")
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(DETAIL_SNACKBAR_TAG).fetchSemanticsNode().boundsInRoot.top
        val lastRowBottom = onNodeWithText(LAST_EPISODE).fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(lastRowBottom <= snackbarTop, "last row ends at $lastRowBottom, snackbar starts at $snackbarTop")
    }

    @Composable
    private fun DetailUnderTest(viewModel: DetailViewModel) {
        MuvissTheme(darkTheme = false) {
            GoldenSurface {
                DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenEpisode = {})
            }
        }
    }
}

private val show = MediaId.tmdbTv("4242")
private const val LAST_EPISODE = "The very last episode"
private const val TODAY_EPOCH_DAY = 20_000L

private class DayClock : AppClock {
    override fun nowEpochMs(): Long = TODAY_EPOCH_DAY * 86_400_000L
}

/** One season of enough aired episodes that the list scrolls, ending on [LAST_EPISODE]. */
private val showDetails = MediaDetails(
    summary = MediaSummary(id = show, title = "Snackbar Show", year = 2024, overview = "A show.", rating = 7.0),
    genres = listOf("Drama"),
    runtimeMinutes = 45,
    productionStatus = ProductionStatus.RETURNING,
    seasons = listOf(
        Season(
            number = 1,
            name = "Season 1",
            episodes = (1..12).map { number ->
                Episode(
                    id = EpisodeId(show, 1, number),
                    seasonNumber = 1,
                    episodeNumber = number,
                    name = if (number == 12) LAST_EPISODE else "Episode $number",
                    airDateEpochDay = 19_000L + number,
                    runtimeMinutes = 45,
                )
            },
        ),
    ),
)

private fun showViewModel(): DetailViewModel {
    val repo = FakeDetailRepo(details = showDetails)
    val collection = FakeCollectionApi().apply { runBlocking { add(showDetails) } }
    return DetailViewModel(
        show,
        MediaDetailUseCase(repo),
        DetailPeers(collection, FakeProgressApi(), FakeTriageApi(), FakeMutingApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        DayClock(),
    )
}
