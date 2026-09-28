@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.cowatch.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.cowatch.api.CompanionState
import com.codingpit.muviss.feature.cowatch.api.PoolSettings
import com.codingpit.muviss.feature.cowatch.api.PoolSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives the real [CompanionsScreen] over a fake [com.codingpit.muviss.feature.cowatch.api.CoWatchApi]:
 * invite, paste-a-code, confirm, rename, unlink, and the "include things
 * you've seen" switch — everything between the ViewModel and a pixel that
 * #130 found untested.
 */
class CompanionsScreenTest {

    // The screen collects with `collectAsStateWithLifecycle`, which hops to Dispatchers.Main.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun with_no_code_yet_it_offers_to_create_one() = runComposeUiTest {
        val api = FakeCoWatchApi()
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithText("Create a code").assertIsDisplayed()
    }

    @Test
    fun creating_an_invite_shows_the_code_and_can_be_dismissed() = runComposeUiTest {
        val api = FakeCoWatchApi().apply { createInviteResult = Result.success("MUVISS-7742") }
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithText("Create a code").performClick()
        waitForIdle()
        onNodeWithText("MUVISS-7742").assertIsDisplayed()

        onNodeWithText("Done").performClick()
        waitForIdle()
        onNodeWithText("MUVISS-7742").assertDoesNotExist()
        onNodeWithText("Create a code").assertIsDisplayed()
    }

    @Test
    fun a_failed_invite_shows_an_error_that_can_be_dismissed() = runComposeUiTest {
        val api = FakeCoWatchApi().apply { createInviteResult = Result.failure(IllegalStateException("not signed in")) }
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithText("Create a code").performClick()
        waitForIdle()
        onNodeWithText("Sign in before inviting someone.").assertIsDisplayed()

        onNodeWithText("Dismiss").performClick()
        waitForIdle()
        onNodeWithText("Sign in before inviting someone.").assertDoesNotExist()
    }

    @Test
    fun pasting_a_code_links_and_clears_the_field() = runComposeUiTest {
        val api = FakeCoWatchApi()
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithTag(PASTE_CODE_FIELD_TAG).performTextInput("THEIR-CODE")
        onNodeWithText("Link").performClick()
        waitForIdle()

        assertEquals(listOf("THEIR-CODE"), api.acceptedCodes)
        // Cleared back to blank: the Link button is disabled again.
        onNodeWithText("Link").assertIsNotEnabled()
    }

    @Test
    fun a_code_that_doesnt_look_right_shows_an_error() = runComposeUiTest {
        val api = FakeCoWatchApi().apply { acceptInviteResult = Result.failure(IllegalArgumentException("bad code")) }
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithTag(PASTE_CODE_FIELD_TAG).performTextInput("garbage")
        onNodeWithText("Link").performClick()
        waitForIdle()

        onNodeWithText("That code didn't look right.").assertIsDisplayed()
    }

    /**
     * A link is two independent statements, one per side (CoWatchApi KDoc), so
     * each of the four combinations gets its own honest sentence. REVOKED's in
     * particular states the limit of unlinking rather than implying a reach it
     * does not have (ADR 0022) — pinned here so it cannot be quietly reworded.
     */
    @Test
    fun each_companion_state_says_where_the_link_stands() = runComposeUiTest {
        val api = FakeCoWatchApi(
            companions = listOf(
                companion("invited", CompanionState.INVITED),
                companion("awaiting", CompanionState.AWAITING_CONFIRMATION),
                companion("active", CompanionState.ACTIVE),
                companion("revoked", CompanionState.REVOKED),
            ),
        )
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        // The column scrolls (four cards plus the invite/paste sections do not
        // all fit the test window), so each row is scrolled into view before
        // its visibility is asserted.
        onNodeWithText("Waiting for them to accept.").performScrollTo().assertIsDisplayed()
        onNodeWithText("They're ready — confirm to start sharing.").performScrollTo().assertIsDisplayed()
        onNodeWithText("Linked.").performScrollTo().assertIsDisplayed()
        onNodeWithText("Unlinked. They may keep a copy until their app next syncs.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun confirming_reaches_the_api() = runComposeUiTest {
        val api = FakeCoWatchApi(companions = listOf(companion("pal", CompanionState.AWAITING_CONFIRMATION)))
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithText("Confirm").performClick()
        waitForIdle()

        assertEquals(listOf("pal"), api.confirmed)
    }

    @Test
    fun only_an_active_companion_offers_what_to_watch() = runComposeUiTest {
        val api = FakeCoWatchApi(companions = listOf(companion("pal", CompanionState.AWAITING_CONFIRMATION)))
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithText("What to watch").assertDoesNotExist()
    }

    @Test
    fun what_to_watch_opens_the_shortlist_for_that_companion() = runComposeUiTest {
        val api = FakeCoWatchApi(companions = listOf(companion("pal", CompanionState.ACTIVE)))
        var opened: String? = null
        setContent {
            MuvissTheme(darkTheme = false) {
                CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = { opened = it })
            }
        }
        waitForIdle()

        onNodeWithText("What to watch").performClick()

        assertEquals("pal", opened)
    }

    @Test
    fun unlinking_reaches_the_api() = runComposeUiTest {
        val api = FakeCoWatchApi(companions = listOf(companion("pal", CompanionState.ACTIVE)))
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithText("Unlink").performClick()
        waitForIdle()

        assertEquals(listOf("pal"), api.unlinked)
    }

    @Test
    fun renaming_reaches_the_api_and_returns_to_the_read_view() = runComposeUiTest {
        val api = FakeCoWatchApi(companions = listOf(companion("pal", CompanionState.ACTIVE, localName = "Alex")))
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithText("Rename").performClick()
        onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
        onNodeWithTag(RENAME_FIELD_TAG).performTextInput("Sam")
        onNodeWithText("Save").performClick()
        waitForIdle()

        assertEquals(listOf<Pair<String, String?>>("pal" to "Sam"), api.renamed)
        onNodeWithText("Sam").assertIsDisplayed()
        onNodeWithTag(RENAME_FIELD_TAG).assertDoesNotExist()
    }

    @Test
    fun clearing_a_name_falls_back_to_the_default_label() = runComposeUiTest {
        val api = FakeCoWatchApi(companions = listOf(companion("pal", CompanionState.ACTIVE, localName = "Alex")))
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onNodeWithText("Rename").performClick()
        onNodeWithTag(RENAME_FIELD_TAG).performTextClearance()
        onNodeWithText("Save").performClick()
        waitForIdle()

        assertEquals(listOf<Pair<String, String?>>("pal" to null), api.renamed)
        onNodeWithText("Someone").assertIsDisplayed()
    }

    @Test
    fun the_seen_toggle_reflects_and_updates_the_stored_setting() = runComposeUiTest {
        val api = FakeCoWatchApi(settings = PoolSettings(PoolSource.NotStarted, includeSeenByDefault = true))
        setContent { MuvissTheme(darkTheme = false) { CompanionsScreen(CompanionsViewModel(api), onBack = {}, onOpenShortlist = {}) } }
        waitForIdle()

        onAllNodes(isToggleable()).onLast().assertIsOn()

        onAllNodes(isToggleable()).onLast().performClick()
        waitForIdle()

        assertEquals(listOf(false), api.includeSeenSet)
        onAllNodes(isToggleable()).onLast().assertIsOff()
    }
}
