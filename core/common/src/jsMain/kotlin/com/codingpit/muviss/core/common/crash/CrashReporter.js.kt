package com.codingpit.muviss.core.common.crash

/**
 * No-op on the web targets, by design and stated in `docs/PRIVACY.md`: the web
 * app does not depend on the Sentry SDK at all (see `:core:common`'s
 * `build.gradle.kts`, where the dependency is scoped to android/iOS/jvm), so
 * nothing on web ever leaves the browser. Browser crash reporting is its own
 * piece of work — a different SDK, and a Content-Security-Policy to open a
 * connection through — tracked in issue #83.
 */
internal actual fun platformCrashBackend(): CrashBackend = NoOpBackend

private object NoOpBackend : CrashBackend {
    override fun start(config: CrashReportingConfig, gate: CrashReportGate) = Unit

    override fun capture(throwable: Throwable) = Unit
}
