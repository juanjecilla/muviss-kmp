package com.codingpit.muviss.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a finished cycle is reported to an OS scheduler. A plain function so it
 * is testable without WorkManager or BGTaskScheduler: `SyncWorker` and the iOS
 * refresh handler only translate the answer into their own vocabulary.
 */
class BackgroundSyncResultTest {

    private fun failed(reason: SyncFailureReason) = SyncOutcome.Failed(reason, "detail")

    @Test
    fun a_completed_cycle_is_a_success() {
        assertEquals(BackgroundSyncResult.Success, SyncOutcome.Success(pushedCount = 3, pulledCount = 1, syncedAtEpochMs = 1L).toBackgroundResult())
    }

    @Test
    fun a_transient_failure_is_retried() {
        listOf(SyncFailureReason.Offline, SyncFailureReason.Server, SyncFailureReason.Unknown).forEach {
            assertEquals(BackgroundSyncResult.Retry, failed(it).toBackgroundResult(), "$it")
        }
    }

    @Test
    fun a_dead_session_is_not_retried() {
        assertEquals(BackgroundSyncResult.Failure, failed(SyncFailureReason.Unauthorised).toBackgroundResult())
    }

    @Test
    fun a_run_that_had_nothing_to_do_is_a_success_not_a_retry() {
        // Retrying a switched-off or signed-out run would loop on backoff for
        // a condition only the user can change.
        assertEquals(BackgroundSyncResult.Success, SyncOutcome.Disabled.toBackgroundResult())
        assertEquals(BackgroundSyncResult.Success, SyncOutcome.NotSignedIn.toBackgroundResult())
        assertEquals(BackgroundSyncResult.Success, SyncOutcome.NotEntitled.toBackgroundResult())
    }

    @Test
    fun a_run_stopped_by_an_account_change_is_a_failure() {
        assertEquals(BackgroundSyncResult.Failure, SyncOutcome.AccountChanged("a", "b").toBackgroundResult())
    }
}
