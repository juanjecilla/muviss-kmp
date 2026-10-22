package com.codingpit.muviss.feature.profile.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which single line the status shows; the words for it are the UI's (see `SyncCopyTest` in `:feature:profile:ui`). */
class SyncPresentationTest {

    @Test
    fun a_quiet_status_has_nothing_to_add() {
        assertNull(syncStatusDetail(SyncStatus(lastSyncedAtEpochMs = 1L)))
    }

    @Test
    fun unsent_changes_are_reported_with_their_count() {
        assertEquals(SyncStatusDetail.Waiting(4), syncStatusDetail(SyncStatus(pendingChanges = 4)))
    }

    @Test
    fun a_failure_outranks_waiting_changes() {
        val detail = syncStatusDetail(SyncStatus(pendingChanges = 4, lastFailure = SyncFailureKind.Offline))

        assertEquals(SyncStatusDetail.Failed(SyncFailureKind.Offline), detail)
    }

    @Test
    fun an_account_mismatch_outranks_everything() {
        val detail = syncStatusDetail(SyncStatus(pendingChanges = 4, lastFailure = SyncFailureKind.Server, accountChanged = true))

        assertEquals(SyncStatusDetail.AccountChanged, detail)
    }
}
