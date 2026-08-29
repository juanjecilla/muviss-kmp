package com.codingpit.muviss.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.codingpit.muviss.core.common.widget.AppWidgets
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Redraws the widgets when the day rolls over.
 *
 * "Next unseen *aired* episode" is a question whose answer changes at
 * midnight with nothing having been written: an episode dated tomorrow
 * becomes an episode dated today, and a widget that only ever refreshes on a
 * write would go on saying "nothing to watch" until the app was opened.
 *
 * A one-shot job that re-arms itself, rather than a periodic one: WorkManager
 * clamps periodic work to a 15-minute minimum but gives no control over
 * *phase*, so a 24-hour period would drift to whatever time the app first
 * launched. What matters here is the boundary, not the interval.
 */
class WidgetMidnightRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppWidgets.refresh()
        WidgetMidnightRefresh.schedule(applicationContext)
        return Result.success()
    }
}

/** Arms [WidgetMidnightRefreshWorker] for the next local midnight. */
object WidgetMidnightRefresh {

    private const val WORK_NAME = "widget_midnight_refresh"

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetMidnightRefreshWorker>()
            .setInitialDelay(millisUntilNextMidnight(), TimeUnit.MILLISECONDS)
            .build()

        // REPLACE, not KEEP: this is re-armed by its own completion and by
        // every process start, and the *phase* is the whole point — a stale
        // request queued against yesterday's midnight would fire immediately
        // and then re-arm correctly, but keeping it would leave two.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Local midnight, from the device's own calendar rather than a UTC
     * epoch-day computation.
     *
     * The app compares air dates in UTC epoch days
     * (`AppClock.todayEpochDay`), which is what makes them comparable across
     * time zones — but a person's "today" starts at their midnight, and that
     * is when they expect the widget to have moved on. Firing on the local
     * boundary covers the UTC one within a day either way.
     *
     * `java.util.Calendar` rather than `java.time`: minSdk is 24 and the
     * module has no core-library desugaring configured.
     */
    internal fun millisUntilNextMidnight(now: Long = System.currentTimeMillis()): Long {
        val midnight = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return midnight.timeInMillis - now
    }
}
