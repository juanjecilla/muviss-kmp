package com.codingpit.muviss

import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.crash.CrashReporter
import com.codingpit.muviss.core.common.crash.CrashReportingConfig
import com.codingpit.muviss.core.database.CrashReportsConsent
import com.codingpit.muviss.core.database.DatabaseDriverFactory
import com.codingpit.muviss.feature.settings.api.SettingsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools
import kotlin.concurrent.Volatile

/**
 * How every host starts crash reporting: **first, before Koin.**
 *
 * `MuvissApp()` used to be the only place, inside a `remember {}`. On Android
 * that meant a process started by `NewEpisodesWorker` — no Activity, no Compose
 * tree — never had a reporter, and neither did a crash while the Koin graph was
 * still being built or before the first frame: every failure this app most
 * needs to hear about. Each host now calls [start] as the first thing it does
 * (`MuvissApplication.onCreate`, desktop's `main`, `IosAppStartup.start`), and
 * `MuvissApp()` keeps [ensureStarted] as a guard for a host that forgot.
 *
 * Lives here rather than in `:core:common` because it needs three things only
 * this module sees: the generated `MuvissBuildConfig` (internal), the database
 * (for the stored consent) and the settings feature's `:api`.
 */
object MuvissCrashReporting {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Starts reporting, honouring the stored "Send crash reports" choice.
     *
     * The choice lives in the database and the database is opened by the graph
     * that does not exist yet, so [CrashReportsConsent] reads it through a
     * short-lived driver of its own. Anything that goes wrong there reads as
     * *on*, the setting's default — see its KDoc for the trade.
     */
    fun start(driverFactory: DatabaseDriverFactory) = start(driverFactory, CrashReporter::init)

    internal fun start(driverFactory: DatabaseDriverFactory, init: (CrashReportingConfig, Boolean) -> Unit) {
        init(config(), CrashReportsConsent.read(driverFactory))
    }

    /**
     * `MuvissApp()`'s guard. A no-op whenever a host already called [start]
     * (the reporter is idempotent), and a no-op on web, where there is no
     * reporter. It has no way to learn the stored consent from here, so if it
     * ever *is* the first caller it starts with reporting off rather than guess:
     * losing a report is recoverable, sending one from someone who opted out is
     * not. [followSettings] turns it on if their setting says so.
     */
    fun ensureStarted() = CrashReporter.init(config(), enabled = false)

    /**
     * Keeps the reporter's live switch in step with the Settings toggle, so
     * turning reports off stops them at once rather than at the next launch.
     * Call after Koin starts; resolves [SettingsApi] on the background scope so
     * startup does not wait on it.
     */
    fun followSettings() {
        if (following) return
        following = true
        follow(scope, CrashReporter::setEnabled) { KoinPlatformTools.defaultContext().get().get<SettingsApi>() }
    }

    @Volatile
    private var following = false

    internal fun follow(scope: CoroutineScope, setEnabled: (Boolean) -> Unit, settings: () -> SettingsApi) {
        scope.launch {
            // A graph that cannot be built is reported by the reporter itself —
            // the crash handler it installed — so nothing is caught here.
            settings().observeCrashReportsEnabled().collect(setEnabled)
        }
    }

    internal fun config(version: AppVersion = AppVersion.current): CrashReportingConfig = CrashReportingConfig(
        dsn = MuvissBuildConfig.SENTRY_DSN,
        environment = MuvissBuildConfig.SENTRY_ENVIRONMENT,
        release = releaseOf(version),
        dist = version.versionCode.toString(),
    )

    /**
     * The exact string the Sentry Gradle plugin stamps on the mapping it uploads
     * (`<applicationId>@<versionName>+<versionCode>`). If the two ever differ,
     * Sentry has no way to tell which mapping deobfuscates which trace.
     */
    internal fun releaseOf(version: AppVersion): String = "$APPLICATION_ID@${version.versionName}+${version.versionCode}"

    internal const val APPLICATION_ID = "com.codingpit.muviss"
}
