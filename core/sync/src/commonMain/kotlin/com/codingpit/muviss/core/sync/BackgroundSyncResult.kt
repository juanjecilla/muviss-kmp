package com.codingpit.muviss.core.sync

/** What an OS-scheduled background run reports back to its scheduler. */
enum class BackgroundSyncResult {
    Success,
    Retry,
    Failure,
}

/**
 * A transient failure asks the scheduler to try again on its own backoff. A run
 * that had nothing to do (switched off, signed out, not entitled) is a success:
 * retrying it would loop on a condition only the user can change. A dead
 * session and an account mismatch are failures for the same reason but say so.
 */
fun SyncOutcome.toBackgroundResult(): BackgroundSyncResult = when (this) {
    is SyncOutcome.Success -> BackgroundSyncResult.Success
    is SyncOutcome.Failed -> if (reason == SyncFailureReason.Unauthorised) BackgroundSyncResult.Failure else BackgroundSyncResult.Retry
    is SyncOutcome.AccountChanged -> BackgroundSyncResult.Failure
    SyncOutcome.Disabled, SyncOutcome.NotSignedIn, SyncOutcome.NotEntitled -> BackgroundSyncResult.Success
}
