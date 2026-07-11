package com.codingpit.muviss.ios

import com.codingpit.muviss.feature.collection.api.CollectionApi
import com.codingpit.muviss.feature.settings.api.SettingsApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError

/**
 * iOS counterpart of `:app:androidApp`'s `NewEpisodesScheduler` +
 * `NewEpisodesWorker` pair (EPIC 5). `BGTaskScheduler` has no periodic
 * primitive like `PeriodicWorkRequestBuilder` — every run is a one-shot
 * request that must be re-submitted for the next run, which [handle] does
 * before completing.
 *
 * Resolves `CollectionApi`/`SettingsApi` via [KoinComponent] rather than a
 * constructor for the same reason `NewEpisodesWorker`'s doc comment gives:
 * `BGTaskScheduler`'s launch handler hands back a plain `BGTask`, not
 * anything Koin can construct through. This requires Koin to already be
 * running globally, guaranteed by [IosAppStartup.start] having run first.
 */
object IosBackgroundRefresh : KoinComponent {

    private const val TASK_ID = "com.codingpit.muviss.refresh"
    private const val REPEAT_INTERVAL_SECONDS = 12.0 * 60.0 * 60.0

    private val collectionApi: CollectionApi by inject()
    private val settingsApi: SettingsApi by inject()

    private var runningJob: Job? = null

    /**
     * Must run before the app finishes launching — Apple's own requirement
     * for `registerForTaskWithIdentifier`, satisfied by calling this from
     * [IosAppStartup.start], itself invoked from the thin Swift
     * `AppDelegate.application(_:didFinishLaunchingWithOptions:)`.
     */
    fun register() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = TASK_ID,
            usingQueue = null,
        ) { task ->
            if (task != null) handle(task)
        }
    }

    /**
     * Submits (or re-submits) the next run ~12h out — the same
     * [REPEAT_INTERVAL_SECONDS] Android's `NewEpisodesScheduler` uses.
     * Submitting again with the same identifier replaces any still-pending
     * request rather than erroring, so calling this both at every launch
     * and after every run (see [handle]) is intentional and idempotent,
     * mirroring `NewEpisodesScheduler`'s `ExistingPeriodicWorkPolicy.KEEP`.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun scheduleNext() {
        val request = BGAppRefreshTaskRequest(identifier = TASK_ID)
        request.earliestBeginDate =
            NSDate(timeIntervalSinceReferenceDate = NSDate().timeIntervalSinceReferenceDate + REPEAT_INTERVAL_SECONDS)
        // submitTaskRequest:error: is an NSError**-style ObjC API; Kotlin/Native
        // surfaces the out-param directly rather than as a thrown exception.
        // Best-effort, same spirit as NewEpisodesWorker's Result.retry(): a
        // failed submit (e.g. too many already-pending requests) just means the
        // next launch's register()+scheduleNext() call tries again.
        memScoped {
            val errorVar = alloc<ObjCObjectVar<NSError?>>()
            BGTaskScheduler.sharedScheduler.submitTaskRequest(request, errorVar.ptr)
        }
    }

    private fun handle(task: BGTask) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        task.expirationHandler = {
            runningJob?.cancel()
        }
        runningJob = scope.launch {
            runCatching {
                val notificationsEnabled = settingsApi.observeNotificationsEnabled().first()
                if (notificationsEnabled) {
                    val newEpisodes = collectionApi.refreshAndFindNewEpisodes()
                    if (newEpisodes.isNotEmpty()) {
                        IosNotificationCenter.postNewEpisodeNotifications(newEpisodes)
                    }
                }
            }
            scheduleNext()
            task.setTaskCompletedWithSuccess(true)
        }
    }
}
