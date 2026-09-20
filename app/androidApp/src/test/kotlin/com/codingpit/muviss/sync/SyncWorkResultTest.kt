package com.codingpit.muviss.sync

import androidx.work.ListenableWorker.Result
import com.codingpit.muviss.core.sync.BackgroundSyncResult
import com.codingpit.muviss.core.sync.SyncFailureReason
import com.codingpit.muviss.core.sync.SyncOutcome
import com.codingpit.muviss.core.sync.toBackgroundResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The whole decision a `SyncWorker` run makes about what to tell WorkManager,
 * as a plain JVM test: the worker itself needs a Context and a Koin graph, and
 * is only verified on a device.
 */
class SyncWorkResultTest {

    @Test
    fun the_three_verdicts_map_to_workmanagers_three_results() {
        assertEquals(Result.success(), BackgroundSyncResult.Success.toWorkResult())
        assertEquals(Result.retry(), BackgroundSyncResult.Retry.toWorkResult())
        assertEquals(Result.failure(), BackgroundSyncResult.Failure.toWorkResult())
    }

    @Test
    fun an_offline_run_is_retried_by_workmanager() {
        val outcome = SyncOutcome.Failed(SyncFailureReason.Offline, "no route")

        assertEquals(Result.retry(), outcome.toBackgroundResult().toWorkResult())
    }

    @Test
    fun a_run_that_was_disabled_is_not_retried() {
        assertEquals(Result.success(), SyncOutcome.Disabled.toBackgroundResult().toWorkResult())
    }

    @Test
    fun a_dead_session_fails_the_run() {
        val outcome = SyncOutcome.Failed(SyncFailureReason.Unauthorised, "401")

        assertEquals(Result.failure(), outcome.toBackgroundResult().toWorkResult())
    }
}
