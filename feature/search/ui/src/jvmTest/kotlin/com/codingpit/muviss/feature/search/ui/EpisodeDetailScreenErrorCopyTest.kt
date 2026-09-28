@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.search.domain.EpisodeDetailUseCase
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A failed episode load renders the mapped copy on Episode Detail, and the
 * raw exception text never reaches a node. Mirrors [SearchScreenErrorCopyTest].
 */
class EpisodeDetailScreenErrorCopyTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_rate_limit_shows_the_rate_limit_copy() = runComposeUiTest {
        setContent { EpisodeDetailWithFailure(MetadataError.RateLimited(retryAfterSeconds = 30)) }
        waitForIdle()

        onNodeWithText(MetadataError.RateLimited().userMessage).assertExists()
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun an_offline_failure_shows_the_offline_copy() = runComposeUiTest {
        setContent { EpisodeDetailWithFailure(MetadataError.Offline()) }
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage).assertExists()
    }

    @Test
    fun a_raw_exception_shows_generic_copy_and_never_its_message() = runComposeUiTest {
        val leak = "Timeout for https://api.themoviedb.org/3/tv/1399/season/3/episode/9?api_key=SECRET"
        setContent { EpisodeDetailWithFailure(IllegalStateException(leak)) }
        waitForIdle()

        onNodeWithText("Something went wrong").assertExists()
        onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        onNodeWithText("themoviedb", substring = true).assertDoesNotExist()
    }
}

private val episodeDetailId = EpisodeId(MediaId.tmdbTv("1399"), 3, 9)

@Composable
private fun EpisodeDetailWithFailure(failure: Throwable) {
    val repo = FakeEpisodeRepo(Result.failure(failure))
    val viewModel = EpisodeDetailViewModel(episodeDetailId, EpisodeDetailUseCase(repo), FakeProgressApi())
    MuvissTheme(darkTheme = false) {
        EpisodeDetailScreen(viewModel = viewModel, onBack = {})
    }
}
