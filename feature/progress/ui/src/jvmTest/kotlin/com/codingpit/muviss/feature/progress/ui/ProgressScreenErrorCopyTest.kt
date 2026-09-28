@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.feature.progress.domain.WatchNextUseCase
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A failed Watch Next load renders the mapped copy on the Progress screen's
 * default tab, and the raw exception text never reaches a node. Mirrors
 * [SearchScreenErrorCopyTest]'s shape (`feature/search/ui`).
 */
class ProgressScreenErrorCopyTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_rate_limit_shows_the_rate_limit_copy() = runComposeUiTest {
        setContent { ProgressWithFailure(MetadataError.RateLimited(retryAfterSeconds = 30)) }
        waitForIdle()

        onNodeWithText(MetadataError.RateLimited().userMessage).assertExists()
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun an_offline_failure_shows_the_offline_copy() = runComposeUiTest {
        setContent { ProgressWithFailure(MetadataError.Offline()) }
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage).assertExists()
    }

    @Test
    fun a_raw_exception_shows_generic_copy_and_never_its_message() = runComposeUiTest {
        val leak = "Timeout for https://api.themoviedb.org/3/tv/1399?api_key=SECRET"
        setContent { ProgressWithFailure(IllegalStateException(leak)) }
        waitForIdle()

        onNodeWithText("Something went wrong").assertExists()
        onNodeWithText("SECRET", substring = true).assertDoesNotExist()
        onNodeWithText("themoviedb", substring = true).assertDoesNotExist()
    }
}

@Composable
private fun ProgressWithFailure(failure: Throwable) {
    val failingCollectionApi = FakeCollectionApi(emptyList(), failure = failure)
    val progressRepository = FakeProgressRepository()
    val watchNextCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeEpisodeCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val watchNextViewModel = ProgressViewModel(
        WatchNextUseCase({ failingCollectionApi }, ObserveSeenEpisodesUseCase(progressRepository), watchNextCache, FakeClock(0L)),
        ToggleEpisodeSeenUseCase(progressRepository),
        watchNextCache,
    )

    val okCollectionApi = FakeUpcomingCollectionApi(emptyList())
    val upcomingCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeUpcomingCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val upcomingViewModel = UpcomingViewModel(okCollectionApi, upcomingCache, FakeUpcomingClock(0L))

    MuvissTheme(darkTheme = false) {
        // Default tab is Watch Next, which is what carries the failure above.
        ProgressScreen(watchNextViewModel = watchNextViewModel, upcomingViewModel = upcomingViewModel, onOpenDetail = {}, onOpenCoWatch = {})
    }
}
