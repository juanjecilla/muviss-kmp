@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.core.testing.FakeFeatureFlags
import com.codingpit.muviss.feature.progress.domain.CompletionDetector
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveLastWatchedAtUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
import com.codingpit.muviss.feature.progress.domain.TickEpisodeUseCase
import com.codingpit.muviss.feature.progress.domain.ToggleEpisodeSeenUseCase
import com.codingpit.muviss.feature.progress.domain.WatchNextUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "What's next?" on an empty Watch Next (EPIC 51, #545) is the prominent way into the Pick Session: the empty
 * state's action. It is drawn only when the shell hands over a callback.
 */
class ProgressNextPickEntryTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun an_empty_watch_next_offers_what_s_next_and_opening_it_calls_back() = runComposeUiTest {
        var opened = 0
        setContent { ProgressWithEntry(onOpenNextPick = { opened++ }) }
        awaitEmptyWatchNext()

        onNodeWithText(NEXT_PICK_LABEL).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun without_a_callback_an_empty_watch_next_draws_no_what_s_next() = runComposeUiTest {
        setContent { ProgressWithEntry(onOpenNextPick = null) }
        awaitEmptyWatchNext()

        onNodeWithText(NEXT_PICK_LABEL).assertDoesNotExist()
    }

    // Watch Next loads off the test dispatcher, so waitForIdle returns before its empty state is composed.
    private fun ComposeUiTest.awaitEmptyWatchNext() = waitUntil(timeoutMillis = 5_000) {
        onAllNodesWithText(EMPTY_TITLE).fetchSemanticsNodes().isNotEmpty()
    }

    private companion object {
        const val EMPTY_TITLE = "Nothing to watch next"
        const val NEXT_PICK_LABEL = "What's next?"
    }
}

@Composable
private fun ProgressWithEntry(onOpenNextPick: (() -> Unit)?) {
    val collectionApi = FakeCollectionApi(emptyList())
    val progressRepository = FakeProgressRepository()
    val watchNextCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeEpisodeCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val watchNextViewModel = ProgressViewModel(
        WatchNextUseCase({ collectionApi }, ObserveSeenEpisodesUseCase(progressRepository), ObserveLastWatchedAtUseCase(progressRepository), watchNextCache, FakeClock(0L)),
        TickEpisodeUseCase(ToggleEpisodeSeenUseCase(progressRepository), CompletionDetector.None),
        watchNextCache,
        FakeFeatureFlags(),
    )
    val upcomingCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeUpcomingCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val upcomingViewModel = UpcomingViewModel(FakeUpcomingCollectionApi(emptyList()), upcomingCache, FakeClock(0L))

    MuvissTheme(darkTheme = false) {
        ProgressScreen(watchNextViewModel, upcomingViewModel, onOpenDetail = {}, onOpenCoWatch = null, onOpenNextPick = onOpenNextPick)
    }
}
