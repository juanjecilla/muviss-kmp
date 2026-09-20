package com.codingpit.muviss.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.codingpit.muviss.core.sync.BackgroundSyncResult
import com.codingpit.muviss.core.sync.SyncEngine
import com.codingpit.muviss.core.sync.SyncTrigger
import com.codingpit.muviss.core.sync.toBackgroundResult
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The hourly background sync (EPIC 40, ADR 0021). Scheduled and cancelled by
 * [SyncScheduleController] as the switch flips, and resolving [SyncEngine]
 * through [KoinComponent] for the same reason `NewEpisodesWorker` does:
 * WorkManager constructs workers by reflection, so nothing can be injected,
 * and `MuvissApplication.onCreate` has already started Koin even on a cold
 * start with no Activity.
 *
 * It asks for [SyncTrigger.Periodic] and *nothing else decides whether that
 * runs*: the engine applies the build flag, the switch, the session and the
 * entitlement, so a job left scheduled after the switch went off (or after
 * sign-out) does no work and reports success rather than retrying forever.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params),
    KoinComponent {

    private val engine: SyncEngine by inject()

    override suspend fun doWork(): Result = engine.syncNow(SyncTrigger.Periodic).toBackgroundResult().toWorkResult()
}

/** The translation into WorkManager's vocabulary; the decision itself is `SyncOutcome.toBackgroundResult` in `:core:sync`. */
internal fun BackgroundSyncResult.toWorkResult(): ListenableWorker.Result = when (this) {
    BackgroundSyncResult.Success -> ListenableWorker.Result.success()
    BackgroundSyncResult.Retry -> ListenableWorker.Result.retry()
    BackgroundSyncResult.Failure -> ListenableWorker.Result.failure()
}
