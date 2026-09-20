@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.profile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.feature.profile.domain.AutomaticSyncMode
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncFailureKind
import com.codingpit.muviss.feature.profile.domain.SyncProvider
import com.codingpit.muviss.feature.profile.domain.SyncStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** Pinned, never left to default: see [RewatchUnderTest]. */
@Composable
internal fun SyncSectionUnderTest(sync: SyncUiState, actions: SyncSectionActions = SyncSectionActions(), darkTheme: Boolean = false) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface(height = 520.dp) {
            SyncSection(sync, actions)
        }
    }
}

private val signedIn = SyncAccountState.SignedIn("person@example.com")

@Suppress("LongParameterList") // a state builder: every field is a named default a test may override
internal fun signedInState(
    automaticSync: Boolean = false,
    status: SyncStatus = SyncStatus(lastSyncedAtEpochMs = 0L),
    label: String = "Synced 3m ago",
    syncing: Boolean = false,
    mode: AutomaticSyncMode = AutomaticSyncMode.InBackground,
    available: Boolean = true,
) = SyncUiState(
    account = signedIn,
    status = status,
    lastSyncedLabel = label,
    automaticSync = automaticSync,
    automaticSyncAvailable = available,
    automaticSyncMode = mode,
    syncing = syncing,
)

private fun SemanticsNodeInteraction.stateDescription(): String? = fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

/**
 * The sync section, one state at a time. Nodes are matched by tag: the section
 * repeats words ("Sync automatically" is both a label and a description) and
 * the switch is a toggle row, not a text button.
 */
class SyncSectionTest {

    // --- what is shown at all -------------------------------------------------

    @Test
    fun an_unavailable_build_renders_nothing_at_all() = runComposeUiTest {
        setContent { SyncSectionUnderTest(SyncUiState(account = SyncAccountState.Unavailable, automaticSyncAvailable = true)) }
        waitForIdle()

        onNodeWithTag(SYNC_SECTION_TAG).assertDoesNotExist()
        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).assertDoesNotExist()
    }

    @Test
    fun a_locked_account_offers_the_purchase_and_a_disabled_switch() = runComposeUiTest {
        var unlocked = false
        setContent {
            SyncSectionUnderTest(
                SyncUiState(account = SyncAccountState.Locked(email = null), automaticSyncAvailable = true),
                SyncSectionActions(onUnlockClicked = { unlocked = true }),
            )
        }
        waitForIdle()

        onNodeWithTag(SYNC_UNLOCK_TAG).assertIsDisplayed().performClick()
        assertEquals(true, unlocked)
        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).assertIsNotEnabled()
        onNodeWithTag(SYNC_NOW_TAG).assertDoesNotExist()
    }

    @Test
    fun a_lapsed_subscriber_keeps_sign_out() = runComposeUiTest {
        setContent { SyncSectionUnderTest(SyncUiState(account = SyncAccountState.Locked(email = "person@example.com"))) }
        waitForIdle()

        onNodeWithTag(SYNC_SIGN_OUT_TAG).assertIsDisplayed()
    }

    @Test
    fun signed_out_offers_sign_in_and_a_switch_that_cannot_be_used_yet() = runComposeUiTest {
        var provider: SyncProvider? = null
        setContent {
            SyncSectionUnderTest(
                SyncUiState(account = SyncAccountState.SignedOut, automaticSyncAvailable = true),
                SyncSectionActions(onSignInClicked = { provider = it }),
            )
        }
        waitForIdle()

        onNodeWithTag(syncSignInTag(SyncProvider.GITHUB)).assertIsDisplayed().performClick()
        assertEquals(SyncProvider.GITHUB, provider)
        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).assertIsNotEnabled()
        onNodeWithTag(SYNC_SESSION_EXPIRED_TAG).assertDoesNotExist()
    }

    @Test
    fun a_session_that_died_says_so_and_still_offers_sign_in() = runComposeUiTest {
        setContent { SyncSectionUnderTest(SyncUiState(account = SyncAccountState.SessionExpired)) }
        waitForIdle()

        onNodeWithTag(SYNC_SESSION_EXPIRED_TAG).assertIsDisplayed().assertTextEquals("Session expired, sign in again.")
        onNodeWithTag(syncSignInTag(SyncProvider.GITHUB)).assertIsDisplayed()
    }

    // --- the switch -------------------------------------------------------------

    @Test
    fun a_build_without_background_sync_hides_the_switch_but_keeps_the_rest() = runComposeUiTest {
        setContent { SyncSectionUnderTest(signedInState(available = false)) }
        waitForIdle()

        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).assertDoesNotExist()
        onNodeWithTag(SYNC_NOW_TAG).assertIsDisplayed()
    }

    @Test
    fun signed_in_the_switch_starts_off_and_reports_it_to_talkback() = runComposeUiTest {
        setContent { SyncSectionUnderTest(signedInState(automaticSync = false)) }
        waitForIdle()

        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).assertIsEnabled()
        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).assert(hasContentDescription(AUTOMATIC_SYNC_LABEL))
        assertEquals("Off", onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).stateDescription())
    }

    @Test
    fun signed_in_and_on_reads_on() = runComposeUiTest {
        setContent { SyncSectionUnderTest(signedInState(automaticSync = true)) }
        waitForIdle()

        assertEquals("On", onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).stateDescription())
    }

    @Test
    fun clicking_the_switch_reports_the_new_value() = runComposeUiTest {
        val toggled = mutableListOf<Boolean>()
        setContent { SyncSectionUnderTest(signedInState(automaticSync = false), SyncSectionActions(onAutomaticSyncToggled = { toggled += it })) }
        waitForIdle()

        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).performClick()

        assertEquals(listOf(true), toggled)
    }

    @Test
    fun a_disabled_switch_does_not_report_clicks() = runComposeUiTest {
        val toggled = mutableListOf<Boolean>()
        setContent {
            SyncSectionUnderTest(
                SyncUiState(account = SyncAccountState.SignedOut, automaticSyncAvailable = true),
                SyncSectionActions(onAutomaticSyncToggled = { toggled += it }),
            )
        }
        waitForIdle()

        onNodeWithTag(SYNC_AUTOMATIC_SWITCH_TAG).performClick()

        assertEquals(emptyList(), toggled)
    }

    @Test
    fun the_description_is_honest_about_each_platform() = runComposeUiTest {
        var mode by mutableStateOf(AutomaticSyncMode.InBackground)
        setContent { SyncSectionUnderTest(signedInState(mode = mode)) }
        waitForIdle()

        onNodeWithTag(SYNC_AUTOMATIC_DESCRIPTION_TAG, useUnmergedTree = true).assertTextEquals("Keep this device in sync in the background, even when Muviss is closed.")
        mode = AutomaticSyncMode.WhenSystemAllows
        waitForIdle()
        onNodeWithTag(SYNC_AUTOMATIC_DESCRIPTION_TAG, useUnmergedTree = true).assertTextEquals("Sync when the system allows it. iOS decides when, so it can be hours between runs.")
        mode = AutomaticSyncMode.WhileOpen
        waitForIdle()
        onNodeWithTag(SYNC_AUTOMATIC_DESCRIPTION_TAG, useUnmergedTree = true).assertTextEquals("Sync while Muviss is open. Nothing runs once you close it.")
    }

    // --- the status line ----------------------------------------------------------

    @Test
    fun a_quiet_signed_in_section_shows_only_when_it_last_synced() = runComposeUiTest {
        setContent { SyncSectionUnderTest(signedInState(label = "Synced 3m ago")) }
        waitForIdle()

        onNodeWithTag(SYNC_LAST_SYNCED_TAG).assertTextEquals("Synced 3m ago")
        onNodeWithTag(SYNC_DETAIL_TAG).assertDoesNotExist()
        onNodeWithTag(SYNC_RETRY_TAG).assertDoesNotExist()
    }

    @Test
    fun waiting_changes_are_counted() = runComposeUiTest {
        setContent { SyncSectionUnderTest(signedInState(status = SyncStatus(pendingChanges = 4))) }
        waitForIdle()

        onNodeWithTag(SYNC_DETAIL_TAG).assertTextEquals("4 changes waiting")
        onNodeWithTag(SYNC_RETRY_TAG).assertDoesNotExist()
    }

    @Test
    fun a_failure_says_why_and_offers_retry() = runComposeUiTest {
        var retried = 0
        setContent {
            SyncSectionUnderTest(
                signedInState(status = SyncStatus(lastFailure = SyncFailureKind.Offline)),
                SyncSectionActions(onSyncNowClicked = { retried++ }),
            )
        }
        waitForIdle()

        onNodeWithTag(SYNC_DETAIL_TAG).assertTextEquals("Last sync failed: you seem to be offline")
        onNodeWithTag(SYNC_RETRY_TAG).assertIsDisplayed().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun each_failure_reason_reads_differently_on_screen() = runComposeUiTest {
        var kind by mutableStateOf(SyncFailureKind.Offline)
        setContent { SyncSectionUnderTest(signedInState(status = SyncStatus(lastFailure = kind))) }
        val seen = mutableSetOf<String>()
        SyncFailureKind.entries.forEach {
            kind = it
            waitForIdle()
            seen += onNodeWithTag(SYNC_DETAIL_TAG).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { t -> t.text }
        }

        assertEquals(SyncFailureKind.entries.size, seen.size)
    }

    @Test
    fun an_account_mismatch_is_explained_and_has_no_retry() = runComposeUiTest {
        setContent { SyncSectionUnderTest(signedInState(status = SyncStatus(pendingChanges = 3, lastFailure = SyncFailureKind.Server, accountChanged = true))) }
        waitForIdle()

        onNodeWithTag(SYNC_DETAIL_TAG).assertTextEquals("This device's library belongs to a different account, so nothing is syncing.")
        onNodeWithTag(SYNC_RETRY_TAG).assertDoesNotExist()
    }

    // --- actions --------------------------------------------------------------------

    @Test
    fun sync_now_is_disabled_while_a_sync_is_running() = runComposeUiTest {
        setContent { SyncSectionUnderTest(signedInState(syncing = true)) }
        waitForIdle()

        onNodeWithTag(SYNC_NOW_TAG).assertIsNotEnabled()
        onNodeWithTag(SYNC_RESYNC_TAG).assertIsNotEnabled()
        onNodeWithTag(SYNC_SIGN_OUT_TAG).assertIsNotEnabled()
    }

    @Test
    fun sync_now_calls_back_when_idle() = runComposeUiTest {
        var clicked = 0
        setContent { SyncSectionUnderTest(signedInState(), SyncSectionActions(onSyncNowClicked = { clicked++ })) }
        waitForIdle()

        onNodeWithTag(SYNC_NOW_TAG).assertIsEnabled().performClick()

        assertEquals(1, clicked)
    }

    @Test
    fun resync_everything_asks_before_doing_anything() = runComposeUiTest {
        val calls = mutableListOf<String>()
        var confirming by mutableStateOf(false)
        setContent {
            SyncSectionUnderTest(
                signedInState().copy(confirmingResync = confirming),
                SyncSectionActions(
                    onResyncEverythingRequested = {
                        calls += "requested"
                        confirming = true
                    },
                    onResyncEverythingConfirmed = {
                        calls += "confirmed"
                        confirming = false
                    },
                    onResyncEverythingDismissed = {
                        calls += "dismissed"
                        confirming = false
                    },
                ),
            )
        }
        waitForIdle()
        onNodeWithTag(SYNC_RESYNC_DIALOG_TAG).assertDoesNotExist()

        onNodeWithTag(SYNC_RESYNC_TAG).performClick()
        waitForIdle()
        onNodeWithTag(SYNC_RESYNC_DIALOG_TAG).assertIsDisplayed()
        assertEquals(listOf("requested"), calls, "the button only asks")

        onNodeWithTag(SYNC_RESYNC_CANCEL_TAG).performClick()
        waitForIdle()
        onNodeWithTag(SYNC_RESYNC_DIALOG_TAG).assertDoesNotExist()
        assertEquals(listOf("requested", "dismissed"), calls)

        onNodeWithTag(SYNC_RESYNC_TAG).performClick()
        waitForIdle()
        onNodeWithTag(SYNC_RESYNC_CONFIRM_TAG).performClick()
        assertEquals(listOf("requested", "dismissed", "requested", "confirmed"), calls)
    }

    @Test
    fun resync_everything_is_only_for_a_signed_in_account() = runComposeUiTest {
        setContent { SyncSectionUnderTest(SyncUiState(account = SyncAccountState.SignedOut)) }
        waitForIdle()

        onNodeWithTag(SYNC_RESYNC_TAG).assertDoesNotExist()
    }
}
