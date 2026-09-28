@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.cowatch.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.cowatch.api.Shortlist
import com.codingpit.muviss.feature.cowatch.api.ShortlistReason
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the real [ShortlistScreen] over a fake [com.codingpit.muviss.feature.cowatch.api.CoWatchApi]:
 * the ranked list, the per-row explanation line, the empty state, and the
 * staleness note — everything #130 found untested between the ViewModel and
 * a pixel.
 */
class ShortlistScreenTest {

    // The screen collects with `collectAsStateWithLifecycle`, which hops to Dispatchers.Main.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The common state before either side has published (issue #130): no items,
     * and the note has to say the honest reason rather than implying nothing
     * was found.
     */
    @Test
    fun before_either_side_has_published_it_explains_itself() = runComposeUiTest {
        val api = FakeCoWatchApi()
        setContent { MuvissTheme(darkTheme = false) { ShortlistScreen("pal", ShortlistViewModel(api), onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("Nothing in common yet").assertIsDisplayed()
        onNodeWithText("Their list hasn't arrived yet — it reaches you once they open Muviss.").assertIsDisplayed()
    }

    @Test
    fun once_their_list_arrives_the_note_says_so() = runComposeUiTest {
        val api = FakeCoWatchApi().apply {
            shortlists.value = mapOf("pal" to Shortlist(items = emptyList(), companionPoolPublishedAtEpochMs = 1_000L))
        }
        setContent { MuvissTheme(darkTheme = false) { ShortlistScreen("pal", ShortlistViewModel(api), onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("Their list is as of the last time they opened Muviss.").assertIsDisplayed()
        onNodeWithText("Their list hasn't arrived yet — it reaches you once they open Muviss.").assertDoesNotExist()
    }

    @Test
    fun a_row_explains_both_pinned_and_neither_started() = runComposeUiTest {
        val item = shortlistItem(
            "1",
            runtimeMinutes = 95,
            reasons = setOf(ShortlistReason.BOTH_PINNED, ShortlistReason.NEITHER_STARTED),
        )
        val api = FakeCoWatchApi().apply {
            shortlists.value = mapOf("pal" to Shortlist(items = listOf(item), companionPoolPublishedAtEpochMs = 1_000L))
        }
        setContent { MuvissTheme(darkTheme = false) { ShortlistScreen("pal", ShortlistViewModel(api), onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("you both picked it · neither of you has started it · 95 min").assertIsDisplayed()
    }

    @Test
    fun a_row_explains_a_revisit() = runComposeUiTest {
        val item = shortlistItem("1", runtimeMinutes = null, reasons = setOf(ShortlistReason.REVISIT))
        val api = FakeCoWatchApi().apply {
            shortlists.value = mapOf("pal" to Shortlist(items = listOf(item), companionPoolPublishedAtEpochMs = 1_000L))
        }
        setContent { MuvissTheme(darkTheme = false) { ShortlistScreen("pal", ShortlistViewModel(api), onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("one of you has seen it and would again").assertIsDisplayed()
    }

    @Test
    fun details_opens_the_titles_own_media_id() = runComposeUiTest {
        val item = shortlistItem("42")
        val api = FakeCoWatchApi().apply {
            shortlists.value = mapOf("pal" to Shortlist(items = listOf(item), companionPoolPublishedAtEpochMs = 1_000L))
        }
        var opened: MediaId? = null
        setContent {
            MuvissTheme(darkTheme = false) {
                ShortlistScreen("pal", ShortlistViewModel(api), onBack = {}, onOpenDetail = { opened = it })
            }
        }
        waitForIdle()

        onNodeWithText("Details").performClick()

        assertEquals(item.mediaId, opened)
    }

    /**
     * Two rows, each with its own explanation line. Not asserted by title:
     * `PosterImage` draws the title again as its no-artwork fallback, so a
     * title match finds two nodes per row (CLAUDE.md's "Compose UI tests" trap).
     */
    @Test
    fun several_titles_in_common_all_render_their_own_row() = runComposeUiTest {
        val api = FakeCoWatchApi().apply {
            shortlists.value = mapOf(
                "pal" to Shortlist(
                    items = listOf(
                        shortlistItem("1", title = "First", runtimeMinutes = 90, reasons = setOf(ShortlistReason.BOTH_PINNED)),
                        shortlistItem("2", title = "Second", runtimeMinutes = 130, reasons = setOf(ShortlistReason.REVISIT)),
                    ),
                    companionPoolPublishedAtEpochMs = 1_000L,
                ),
            )
        }
        setContent { MuvissTheme(darkTheme = false) { ShortlistScreen("pal", ShortlistViewModel(api), onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("you both picked it · 90 min").assertIsDisplayed()
        onNodeWithText("one of you has seen it and would again · 130 min").assertIsDisplayed()
        onAllNodesWithText("Details").assertCountEquals(2)
    }

    /**
     * A Shortlist is an intersection: this account's own half has to go out
     * first or it stays permanently empty and looks like the feature not
     * working (`ShortlistViewModel.start` KDoc).
     */
    @Test
    fun starting_publishes_this_accounts_own_pool_first() = runComposeUiTest {
        val api = FakeCoWatchApi()
        setContent { MuvissTheme(darkTheme = false) { ShortlistScreen("pal", ShortlistViewModel(api), onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        assertEquals(1, api.refreshPublishedPoolCalls)
    }
}
