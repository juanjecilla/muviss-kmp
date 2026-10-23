@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.progress.ui

import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.progress.domain.EpisodeCatalogCache
import com.codingpit.muviss.feature.progress.domain.FetchEpisodeCatalogUseCase
import com.codingpit.muviss.models.Episode
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MetadataError
import com.codingpit.muviss.models.Season
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Upcoming list's last row must scroll clear of the snackbar that floats
 * over the bottom of the list (the refresh-failed message, #442).
 */
class UpcomingSnackbarTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_snackbar_does_not_cover_the_last_row_when_the_list_is_scrolled_to_its_end() = runComposeUiTest {
        // Every show airs tomorrow, so the agenda is one bucket: a header, then one row per show.
        val shows = (1..12).map { MediaId.tmdbTv("$it$it$it") }
        val source = FakeUpcomingCatalogSource(
            shows.associateWith { id ->
                listOf(Season(1, "Season 1", listOf(Episode(EpisodeId(id, 1, 1), 1, 1, "Pilot", airDateEpochDay = TOMORROW))))
            },
        )
        val api = FakeUpcomingCollectionApi(shows.mapIndexed { i, id -> CollectionSummary(id, "Show ${i + 1}", posterUrl = null, status = WatchStatus.WATCHING) })
        val cache = EpisodeCatalogCache(FetchEpisodeCatalogUseCase(source), InMemoryEpisodeCatalogStore())
        val viewModel = UpcomingViewModel(api, cache, FakeClock(TODAY_MILLIS))
        setContent {
            MuvissTheme(darkTheme = false) {
                UpcomingScreen(viewModel = viewModel, onOpenDetail = {})
            }
        }
        mainClock.autoAdvance = false
        waitForIdle()

        // A refresh that cannot fetch raises the snackbar; the list is already loaded.
        source.failure = MetadataError.Offline()
        viewModel.refresh()
        waitForIdle()
        mainClock.advanceTimeBy(100)
        onNodeWithText(MetadataError.Offline().userMessage).assertExists()

        onNodeWithTag(UPCOMING_LIST_TAG).performScrollToIndex(shows.size)
        mainClock.advanceTimeBy(500)

        val snackbarTop = onNodeWithTag(UPCOMING_SNACKBAR_TAG).fetchSemanticsNode().boundsInRoot.top
        val lastRowBottom = onAllNodesWithTag(UPCOMING_ROW_TAG).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        assertTrue(lastRowBottom <= snackbarTop, "last row ends at $lastRowBottom, snackbar starts at $snackbarTop")
    }
}

private const val TODAY_MILLIS = 100L * 86_400_000L
private const val TOMORROW = 101L
