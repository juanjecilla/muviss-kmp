package com.codingpit.muviss.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.TimeUnit

/** What [SyncScheduleController] drives; an interface so the controller is testable without WorkManager. */
interface BackgroundSyncScheduler {
    fun schedule()

    fun cancel()
}

/**
 * Arms [SyncWorker] as a unique periodic job, every hour, only with a network
 * and a battery that is not low. [ExistingPeriodicWorkPolicy.KEEP] makes
 * scheduling idempotent (a process start re-schedules and must not reset the
 * next-run bookkeeping), the same choice `NewEpisodesScheduler` makes.
 */
class WorkManagerSyncScheduler(private val context: Context) : BackgroundSyncScheduler {

    override fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "background_sync"
        const val REPEAT_INTERVAL_HOURS = 1L
    }
}

/**
 * Keeps the scheduled job in step with the policy: scheduled while automatic
 * sync is on, cancelled while it is off. It acts on the *first* value too, so
 * a job that outlived a process (WorkManager persists them) is removed at
 * startup if the switch was turned off since — nothing else would.
 */
class SyncScheduleController(
    private val scheduler: BackgroundSyncScheduler,
    private val enabled: Flow<Boolean>,
) {
    suspend fun run() {
        enabled.distinctUntilChanged().collect { on -> if (on) scheduler.schedule() else scheduler.cancel() }
    }
}
