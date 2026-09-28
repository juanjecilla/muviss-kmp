@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppClock
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A failed detail load renders the mapped copy on Detail, and the raw
 * exception text never reaches a node. Mirrors [SearchScreenErrorCopyTest].
 */
class DetailScreenErrorCopyTest {

    private val mediaId = MediaId.tmdbMovie("603")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_rate_limit_shows_the_rate_limit_copy() = runComposeUiTest {
        setContent { DetailWithFailure(MetadataError.RateLimited(retryAfterSeconds = 30)) }
        waitForIdle()

        onNodeWithText(MetadataError.RateLimited().userMessage).assertExists()
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun an_offline_failure_shows_the_offline_copy() = runComposeUiTest {
        setContent { DetailWithFailure(MetadataError.Offline()) }
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage).assertExists()
    }

    @Test
    fun a_raw_exception_shows_generic_copy_and_never_its_message() = runComposeUiTest {
        val leak = "Fields [id, title] are required for type MediaDetails; url=https://api.themoviedb.org/3/movie/603?api_key=SECRET"
        setContent { DetailWithFailure(IllegalStateException(leak)) }
        waitForIdle()

        onNodeWithText("Something went wrong").assertExists()
        onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        onNodeWithText("themoviedb", substring = true).assertDoesNotExist()
    }
}

private class NoopClock : AppClock {
    override fun nowEpochMs(): Long = 0L
}

@Composable
private fun DetailWithFailure(failure: Throwable) {
    val mediaId = MediaId.tmdbMovie("603")
    val repo = FakeDetailRepo(details = MediaDetails(MediaSummary(mediaId, "The Matrix")), detailsFailure = failure)
    val viewModel = DetailViewModel(
        mediaId,
        MediaDetailUseCase(repo),
        DetailPeers(FakeCollectionApi(), FakeProgressApi(), FakeTriageApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        NoopClock(),
    )
    MuvissTheme(darkTheme = false) {
        DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenEpisode = {})
    }
}
