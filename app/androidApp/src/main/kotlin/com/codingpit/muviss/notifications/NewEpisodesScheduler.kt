package com.codingpit.muviss.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules [NewEpisodesWorker] as a unique periodic job. Called once from
 * [com.codingpit.muviss.MuvissApplication.onCreate], so it re-registers on
 * every process start; [ExistingPeriodicWorkPolicy.KEEP] makes that
 * idempotent — WorkManager keeps whatever schedule it already has queued
 * (surviving reboot is WorkManager's own default behavior) instead of
 * resetting its next-run bookkeeping on every launch. Bump [WORK_NAME] if
 * the schedule itself ever needs to change and existing enqueued work must
 * be replaced rather than kept.
 *
 * Constraints match the epic's Doze-friendly requirement: network required
 * (the refresh calls TMDB) and battery-not-low (a background metadata
 * refresh isn't worth draining a low battery over).
 */
object NewEpisodesScheduler {
    private const val WORK_NAME = "new_episodes_refresh"
    private const val REPEAT_INTERVAL_HOURS = 12L

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<NewEpisodesWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
