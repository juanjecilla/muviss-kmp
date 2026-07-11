package com.codingpit.muviss.ios

import com.codingpit.muviss.MuvissBuildConfig
import com.codingpit.muviss.core.common.crash.CrashReporter
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
 * so `MuvissApp()`'s own `remember { CrashReporter.init(...) }` +
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
     * Koin's own `org.koin.mp.KoinPlatformTools`). [CrashReporter.init] is
     * cheap to call again (Sentry's own `init` just reconfigures) so no
     * extra flag is kept for it.
     */
    fun start() {
        CrashReporter.init(MuvissBuildConfig.SENTRY_DSN)
        if (KoinPlatformTools.defaultContext().getOrNull() == null) {
            startKoin {
                modules(appModules + module { single { DatabaseDriverFactory() } })
            }
        }
        IosBackgroundRefresh.register()
        IosBackgroundRefresh.scheduleNext()
        IosNotificationCenter.configure()
    }
}
