@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A failed Upcoming load renders the mapped copy, and the raw exception text
 * never reaches a node. Mirrors [SearchScreenErrorCopyTest]'s shape
 * (`feature/search/ui`).
 */
class UpcomingScreenErrorCopyTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_rate_limit_shows_the_rate_limit_copy() = runComposeUiTest {
        setContent { UpcomingWithFailure(MetadataError.RateLimited(retryAfterSeconds = 30)) }
        waitForIdle()

        onNodeWithText(MetadataError.RateLimited().userMessage).assertExists()
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun an_offline_failure_shows_the_offline_copy() = runComposeUiTest {
        setContent { UpcomingWithFailure(MetadataError.Offline()) }
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage).assertExists()
    }

    @Test
    fun a_raw_exception_shows_generic_copy_and_never_its_message() = runComposeUiTest {
        val leak = "Timeout for https://api.themoviedb.org/3/tv/1399?api_key=SECRET"
        setContent { UpcomingWithFailure(IllegalStateException(leak)) }
        waitForIdle()

        onNodeWithText("Something went wrong").assertExists()
        onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        onNodeWithText("themoviedb", substring = true).assertDoesNotExist()
    }
}

@Composable
private fun UpcomingWithFailure(failure: Throwable) {
    val collectionApi = FakeUpcomingCollectionApi(emptyList(), failure = failure)
    val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeUpcomingCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val viewModel = UpcomingViewModel(collectionApi, cache, FakeClock(0L))

    MuvissTheme(darkTheme = false) {
        UpcomingScreen(viewModel = viewModel, onOpenDetail = {})
    }
}
