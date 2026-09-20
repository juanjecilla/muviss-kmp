package com.codingpit.muviss

import android.app.Application
import com.codingpit.muviss.core.common.widget.AppWidgets
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.core.sync.AutomaticSyncSettings
import com.codingpit.muviss.core.sync.SyncCoordinator
import com.codingpit.muviss.di.appModules
import com.codingpit.muviss.notifications.NewEpisodesScheduler
import com.codingpit.muviss.sync.SyncScheduleController
import com.codingpit.muviss.sync.WorkManagerSyncScheduler
import com.codingpit.muviss.widget.GlanceWidgetRefresher
import com.codingpit.muviss.widget.WidgetMidnightRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Android application entry point.
 *
 * Starts Koin here rather than relying solely on `MuvissApp()`'s
 * `org.koin.compose.KoinApplication` composable, so EPIC 5's
 * `NewEpisodesWorker` can resolve its dependencies even when WorkManager
 * cold-starts this process with no Activity/Compose tree at all (e.g. a
 * periodic run while the app hasn't been opened since boot). Guarded by
 * [GlobalContext.getOrNull] in case something else already started it first.
 *
 * `MuvissApp()` composes its own `KoinApplication` with the same module
 * list; per its doc comment, when a global Koin instance already exists
 * (the normal case once this runs) it reuses that instance instead of
 * starting a second one, so there's exactly one Koin graph either way.
 *
 * Starts crash reporting before anything else (EPIC 26), so it covers this
 * cold-start path too — see [MuvissCrashReporting].
 *
 * It also installs the Glance-backed [WidgetRefresher]
 * [com.codingpit.muviss.core.common.widget.WidgetRefresher] (EPIC 22). This
 * is the first point that has an application `Context`, which is later than
 * the Koin graph that already handed one to the progress repository —
 * `AppWidgets` resolves per call precisely so that ordering does not matter.
 */
class MuvissApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, before Koin: a crash while the graph is built, in a worker that
        // cold-started this process, or before the first frame is the kind this
        // exists to see. It reads the stored opt-out on its own short-lived
        // driver because the graph that owns the database does not exist yet.
        MuvissCrashReporting.start(DatabaseDriverFactory(this))
        if (GlobalContext.getOrNull() == null) {
            startKoin {
                modules(appModules + module { single { DatabaseDriverFactory(this@MuvissApplication) } })
            }
        }
        MuvissCrashReporting.followSettings()
        AppWidgets.install(GlanceWidgetRefresher(this))
        NewEpisodesScheduler.schedule(this)
        WidgetMidnightRefresh.schedule(this)
        startAutomaticSync()
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Keeps the hourly [com.codingpit.muviss.sync.SyncWorker] in step with the
     * switch, and starts the coordinator so a widget action or anything else
     * that writes while no Activity exists still gets pushed (EPIC 40). Both
     * follow `AutomaticSyncSettings.enabled` and the engine still decides on
     * every run, so this widens nothing the switch forbids.
     */
    private fun startAutomaticSync() {
        val koin = GlobalContext.get()
        koin.get<SyncCoordinator>().start()
        val controller = SyncScheduleController(WorkManagerSyncScheduler(this), koin.get<AutomaticSyncSettings>().enabled)
        applicationScope.launch { controller.run() }
    }
}
