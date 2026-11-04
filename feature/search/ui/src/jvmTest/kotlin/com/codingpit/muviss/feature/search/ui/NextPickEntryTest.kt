@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.collection.api.CollectionSummary
import com.codingpit.muviss.feature.search.domain.DiscoverMediaUseCase
import com.codingpit.muviss.feature.search.domain.GenresUseCase
import com.codingpit.muviss.feature.search.domain.MediaDetailUseCase
import com.codingpit.muviss.feature.search.domain.MoreLikeThisUseCase
import com.codingpit.muviss.feature.search.domain.RecommendationsUseCase
import com.codingpit.muviss.feature.search.domain.SearchMediaUseCase
import com.codingpit.muviss.feature.search.domain.SimilarMediaUseCase
import com.codingpit.muviss.feature.search.domain.WatchProvidersUseCase
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import com.codingpit.muviss.models.WatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "What's next?" (EPIC 51, #545) on the two search-feature screens that offer it: Discover, and the Detail of a
 * Watched or Finished title. A null callback draws nothing at all, which is how a peer without the Pick Session
 * (or a test) gets no entry.
 */
class NextPickEntryTest {

    private val mediaId = MediaId.tmdbMovie("603")

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun discover_offers_what_s_next_and_opening_it_calls_back() = runComposeUiTest {
        var opened = 0
        setContent { DiscoverWithEntry(onOpenNextPick = { opened++ }) }
        waitForIdle()

        onNodeWithTag(NEXT_PICK_ENTRY_TAG).assertExists().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun discover_without_a_callback_draws_no_entry() = runComposeUiTest {
        setContent { DiscoverWithEntry(onOpenNextPick = null) }
        waitForIdle()

        onNodeWithTag(NEXT_PICK_ENTRY_TAG).assertDoesNotExist()
    }

    @Test
    fun a_watched_title_offers_what_s_next_and_opening_it_calls_back() = runComposeUiTest {
        var opened = 0
        setContent { DetailWithEntry(WatchStatus.WATCHED, onOpenNextPick = { opened++ }) }
        waitForIdle()

        onNodeWithTag(NEXT_PICK_ENTRY_TAG).assertExists().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun a_finished_title_offers_what_s_next() = runComposeUiTest {
        setContent { DetailWithEntry(WatchStatus.FINISHED, onOpenNextPick = {}) }
        waitForIdle()

        onNodeWithTag(NEXT_PICK_ENTRY_TAG).assertExists()
    }

    @Test
    fun a_title_still_being_watched_offers_no_what_s_next() = runComposeUiTest {
        setContent { DetailWithEntry(WatchStatus.WATCHING, onOpenNextPick = {}) }
        waitForIdle()

        onNodeWithTag(NEXT_PICK_ENTRY_TAG).assertDoesNotExist()
    }

    @Test
    fun a_title_outside_the_library_offers_no_what_s_next() = runComposeUiTest {
        setContent { DetailWithEntry(status = null, onOpenNextPick = {}) }
        waitForIdle()

        onNodeWithTag(NEXT_PICK_ENTRY_TAG).assertDoesNotExist()
    }

    @Test
    fun a_watched_title_without_a_callback_draws_no_entry() = runComposeUiTest {
        setContent { DetailWithEntry(WatchStatus.WATCHED, onOpenNextPick = null) }
        waitForIdle()

        onNodeWithTag(NEXT_PICK_ENTRY_TAG).assertDoesNotExist()
    }
}

@Composable
private fun DiscoverWithEntry(onOpenNextPick: (() -> Unit)?) {
    val repo = FakeRepo()
    val viewModel = SearchViewModel(
        SearchMediaUseCase(repo),
        DiscoverMediaUseCase(repo),
        GenresUseCase(repo),
        RecommendationsUseCase(repo),
        FakeSearchCollectionApi(),
        FakeTriageApi(),
        FakeMutingApi(),
    )
    MuvissTheme(darkTheme = false) {
        SearchScreen(viewModel = viewModel, onOpenDetail = {}, onOpenTriage = {}, onOpenNextPick = onOpenNextPick)
    }
}

@Composable
private fun DetailWithEntry(status: WatchStatus?, onOpenNextPick: (() -> Unit)?) {
    val mediaId = MediaId.tmdbMovie("603")
    val repo = FakeDetailRepo(details = MediaDetails(MediaSummary(mediaId, "The Matrix", year = 1999)))
    val collection = FakeCollectionApi()
    if (status != null) collection.summaries.value = listOf(CollectionSummary(mediaId, "The Matrix", null, status))
    val viewModel = DetailViewModel(
        mediaId,
        MediaDetailUseCase(repo),
        DetailPeers(collection, FakeProgressApi(), FakeTriageApi(), FakeMutingApi()),
        WatchProvidersUseCase(repo),
        MoreLikeThisUseCase(RecommendationsUseCase(repo), SimilarMediaUseCase(repo)),
        FakeClock(0L),
    )
    MuvissTheme(darkTheme = false) {
        DetailScreen(viewModel = viewModel, onBack = {}, onOpenDetail = {}, onOpenEpisode = {}, onOpenNextPick = onOpenNextPick)
    }
}
