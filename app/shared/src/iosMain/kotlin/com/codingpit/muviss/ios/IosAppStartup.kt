package com.codingpit.muviss.ios

import com.codingpit.muviss.MuvissCrashReporting
import com.codingpit.muviss.core.common.widget.AppWidgets
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.di.appModules
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools

/**
 * iOS equivalent of `MuvissApplication.onCreate` (`:app:androidApp`): starts
 * Koin and Sentry *outside* of Compose so EPIC 5's background refresh
 * ([IosBackgroundRefresh]) can resolve `CollectionApi`/`SettingsApi` even
 * when `BGTaskScheduler` launches the process in the background with no
 * `ComposeUIViewController` ever composed — a background BGAppRefreshTask
 * launch does not necessarily instantiate the SwiftUI `WindowGroup`/scene,
 * so `MuvissApp()`'s own `remember { MuvissCrashReporting.ensureStarted() }` +
 * `KoinApplication` composable (see its doc comment) can't be relied on as
 * the *only* startup path the way it can on the other platforms.
 *
 * Called once from Swift's thin `AppDelegate.application(_:didFinishLaunchingWithOptions:)`
 * — the one hook guaranteed to run before launch completes regardless of
 * why the app was launched (user tap, background refresh, notification
 * tap) — so it must be safe to call multiple times (e.g. a foreground
 * launch that later composes `MuvissApp()` too, which reuses the already-
 * running global Koin instance exactly like Android does).
 */
object IosAppStartup {

    /**
     * Idempotent: [KoinPlatformTools.defaultContext]'s `getOrNull()` guards
     * Koin the same way `MuvissApplication.onCreate`'s `GlobalContext.getOrNull()`
     * does (that JVM-only API isn't the portable KMP entry point — see
     * Koin's own `org.koin.mp.KoinPlatformTools`). [MuvissCrashReporting.start] is
     * idempotent too — `CrashReporter.init` starts the SDK once and ignores every
     * later call — and runs *before* Koin (EPIC 26) so a failure while the graph
     * is built is reported.
     *
     * [reloadWidgetTimelines] is the Swift side's
     * `WidgetCenter.shared.reloadAllTimelines()` (EPIC 22). It is a parameter
     * rather than something this object calls because WidgetKit has no
     * Objective-C surface for Kotlin/Native to bind to — see
     * [IosWidgetRefresher]. It defaults to a no-op so a host that has no
     * widget extension yet still compiles and runs.
     */
    fun start(reloadWidgetTimelines: () -> Unit = {}) {
        MuvissCrashReporting.start(DatabaseDriverFactory())
        if (KoinPlatformTools.defaultContext().getOrNull() == null) {
            startKoin {
                modules(appModules + module { single { DatabaseDriverFactory() } })
            }
        }
        MuvissCrashReporting.followSettings()
        AppWidgets.install(IosWidgetRefresher(reloadWidgetTimelines))
        IosBackgroundRefresh.register()
        IosBackgroundRefresh.scheduleNext()
        IosNotificationCenter.configure()
    }
}
