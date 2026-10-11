@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.feature.progress.domain.ObserveSeenEpisodesUseCase
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
 * The co-watch link is shown only when the shell hands over a way in, which it
 * does only for a build with sync: co-watch is built on sync, and release
 * builds ship without it (ADR 0018).
 */
class ProgressCoWatchLinkTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_build_without_sync_shows_no_co_watch_link() = runComposeUiTest {
        setContent { ProgressUnderTest(onOpenCoWatch = null) }
        waitForIdle()

        onNodeWithText(CO_WATCH_LINK).assertDoesNotExist()
    }

    @Test
    fun a_build_with_sync_shows_the_link_and_it_opens_co_watch() = runComposeUiTest {
        var opened = 0
        setContent { ProgressUnderTest(onOpenCoWatch = { opened++ }) }
        waitForIdle()

        onNodeWithText(CO_WATCH_LINK).performClick()
        assertEquals(1, opened)
    }

    private companion object {
        const val CO_WATCH_LINK = "Watch together with someone"
    }
}

@Composable
private fun ProgressUnderTest(onOpenCoWatch: (() -> Unit)?) {
    val collectionApi = FakeCollectionApi(emptyList())
    val progressRepository = FakeProgressRepository()
    val watchNextCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeEpisodeCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val watchNextViewModel = ProgressViewModel(
        WatchNextUseCase({ collectionApi }, ObserveSeenEpisodesUseCase(progressRepository), watchNextCache, FakeClock(0L)),
        ToggleEpisodeSeenUseCase(progressRepository),
        watchNextCache,
    )
    val upcomingCache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(FakeUpcomingCatalogSource(emptyMap())), InMemoryEpisodeCatalogStore())
    val upcomingViewModel = UpcomingViewModel(FakeUpcomingCollectionApi(emptyList()), upcomingCache, FakeClock(0L))

    MuvissTheme(darkTheme = false) {
        ProgressScreen(watchNextViewModel, upcomingViewModel, onOpenDetail = {}, onOpenCoWatch = onOpenCoWatch)
    }
}
