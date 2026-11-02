package com.codingpit.muviss.ios

/**
 * Whether to submit a new `BGAppRefreshTaskRequest`, as a pure decision.
 *
 * Kept apart from [IosBackgroundRefresh] because `BGTaskScheduler` cannot be asked
 * what it would do: its pending list only exists on a device or simulator, and the
 * rule itself is what needs a test.
 */
internal object IosBackgroundRefreshSchedule {

    /**
     * [keepExisting] is true at launch. Submitting again with the same identifier
     * *replaces* a request that is still pending, so a launch that re-submits pushes
     * the next run another twelve hours out each time the app is opened, and a person
     * who opens Muviss daily would never see a background refresh. Keeping the pending
     * request is WorkManager's `ExistingPeriodicWorkPolicy.KEEP`, which is what Android
     * does.
     *
     * [keepExisting] is false after a run has consumed its request. Nothing pending
     * then is the normal case, and a failed pending query must not leave the app with
     * nothing scheduled, so the next run is always submitted.
     */
    fun shouldSubmit(pendingIdentifiers: Collection<String>, taskId: String, keepExisting: Boolean): Boolean = !keepExisting || taskId !in pendingIdentifiers
}
