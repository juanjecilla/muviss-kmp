@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.profile.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.feature.profile.domain.SyncAccountState
import com.codingpit.muviss.feature.profile.domain.SyncFailureKind
import com.codingpit.muviss.feature.profile.domain.SyncStatus
import kotlin.test.Test

private const val TEXT_HEAVY_TOLERANCE = 0.035

/**
 * What the sync section *looks* like in each state, in both themes: that the
 * switch reads as a switch with its description under it, that a failure is
 * distinguishable from a quiet status, and that a disabled switch looks
 * disabled rather than off. The behavioural tests cannot see any of that.
 *
 * `darkTheme` is passed explicitly in every one: left to default it reads the
 * host machine's setting and a golden recorded in dark mode fails on CI.
 */
class SyncSectionGoldenTest {

    private fun ComposeUiTest.golden(name: String, sync: SyncUiState, darkTheme: Boolean) {
        setContent { SyncSectionUnderTest(sync, darkTheme = darkTheme) }
        waitForIdle()
        assertMatchesGolden("sync-section-$name-${if (darkTheme) "dark" else "light"}", tolerance = TEXT_HEAVY_TOLERANCE)
    }

    private val signedInOff = signedInState(automaticSync = false)
    private val signedInOnWaiting = signedInState(automaticSync = true, status = SyncStatus(pendingChanges = 4), label = "Synced just now")
    private val signedInFailed = signedInState(
        automaticSync = true,
        status = SyncStatus(lastFailure = SyncFailureKind.Offline),
        label = "Synced 2h ago",
    )
    private val signedOutDisabledSwitch = SyncUiState(account = SyncAccountState.SignedOut, automaticSyncAvailable = true)
    private val sessionExpired = SyncUiState(account = SyncAccountState.SessionExpired, automaticSyncAvailable = true)

    @Test
    fun signed_in_with_the_switch_off_light() = runComposeUiTest { golden("signed-in-off", signedInOff, darkTheme = false) }

    @Test
    fun signed_in_with_the_switch_off_dark() = runComposeUiTest { golden("signed-in-off", signedInOff, darkTheme = true) }

    @Test
    fun signed_in_on_with_changes_waiting_light() = runComposeUiTest { golden("signed-in-on-waiting", signedInOnWaiting, darkTheme = false) }

    @Test
    fun signed_in_on_with_changes_waiting_dark() = runComposeUiTest { golden("signed-in-on-waiting", signedInOnWaiting, darkTheme = true) }

    @Test
    fun a_failed_sync_light() = runComposeUiTest { golden("failed", signedInFailed, darkTheme = false) }

    @Test
    fun a_failed_sync_dark() = runComposeUiTest { golden("failed", signedInFailed, darkTheme = true) }

    @Test
    fun signed_out_with_a_disabled_switch_light() = runComposeUiTest { golden("signed-out", signedOutDisabledSwitch, darkTheme = false) }

    @Test
    fun signed_out_with_a_disabled_switch_dark() = runComposeUiTest { golden("signed-out", signedOutDisabledSwitch, darkTheme = true) }

    // Deferred, so the golden is the section and its "Choose" button: the dialog
    // is a separate window that the root capture does not include.
    private val accountChangedDeferred = signedInState(status = SyncStatus(accountChanged = true)).copy(accountChoiceDeferred = true)

    @Test
    fun an_account_mismatch_left_for_later_light() = runComposeUiTest { golden("account-changed", accountChangedDeferred, darkTheme = false) }

    @Test
    fun an_account_mismatch_left_for_later_dark() = runComposeUiTest { golden("account-changed", accountChangedDeferred, darkTheme = true) }

    @Test
    fun an_expired_session_light() = runComposeUiTest { golden("session-expired", sessionExpired, darkTheme = false) }

    @Test
    fun an_expired_session_dark() = runComposeUiTest { golden("session-expired", sessionExpired, darkTheme = true) }
}
