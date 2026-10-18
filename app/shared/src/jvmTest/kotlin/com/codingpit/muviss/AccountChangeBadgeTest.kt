package com.codingpit.muviss

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.sync.SyncBackendId
import com.codingpit.muviss.core.sync.SyncSession
import com.codingpit.muviss.core.sync.SyncStatusSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** #274: the account-switch choice is announced from every tab, not only from Profile. */
@OptIn(ExperimentalTestApi::class)
class AccountChangeBadgeTest {

    private fun snapshot(owner: String?) = SyncStatusSnapshot(
        lastSyncedAtEpochMs = null,
        ownerAccountId = owner,
        lastAttemptAtEpochMs = null,
        lastAttemptFailed = false,
        lastFailure = null,
        consecutiveFailures = 0,
        pendingChanges = 0,
    )

    private fun session(userId: String) = SyncSession(SyncBackendId.SUPABASE, userId, "$userId@example.com", "access", "refresh", null)

    private suspend fun pending(owner: String?, session: SyncSession?) = accountChangePending(flowOf(snapshot(owner)), flowOf(session)).first()

    @Test
    fun a_library_owned_by_another_account_is_pending() = runTest {
        assertTrue(pending(owner = "alice", session = session("bob")))
    }

    @Test
    fun the_owner_signed_in_a_first_sync_or_no_session_is_not() = runTest {
        assertFalse(pending(owner = "alice", session = session("alice")))
        assertFalse(pending(owner = null, session = session("bob")), "never synced: nothing to choose between")
        assertFalse(pending(owner = "alice", session = null), "signed out: the next sign-in decides")
    }

    @Test
    fun the_badge_clears_once_the_choice_adopts_the_new_account() = runTest {
        val status = MutableStateFlow(snapshot(owner = "alice"))
        val flow = accountChangePending(status, flowOf(session("bob")))
        assertTrue(flow.first())

        status.value = snapshot(owner = "bob")
        assertEquals(false, flow.first())
    }

    private val profile = muvissDestinations.single { it.key == ACCOUNT_CHANGE_DESTINATION_KEY }

    @Test
    fun the_profile_item_carries_a_badge_and_says_why_while_a_choice_is_pending() = runComposeUiTest {
        setContent { NavItemIcon(profile, needsAttention = true) }

        onNodeWithTag(NAV_ATTENTION_BADGE_TAG).assertIsDisplayed()
        onNodeWithContentDescription("Profile, action needed").assertIsDisplayed()
    }

    @Test
    fun otherwise_it_is_a_plain_icon() = runComposeUiTest {
        setContent { NavItemIcon(profile, needsAttention = false) }

        onNodeWithTag(NAV_ATTENTION_BADGE_TAG).assertDoesNotExist()
        onNodeWithContentDescription("Profile").assertIsDisplayed()
    }
}
