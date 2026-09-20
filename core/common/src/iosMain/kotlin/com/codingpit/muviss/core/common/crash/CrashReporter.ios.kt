package com.codingpit.muviss.core.common.crash

import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb

internal actual fun platformCrashBackend(): CrashBackend = SentryBackend

private object SentryBackend : CrashBackend {
    override fun start(config: CrashReportingConfig, gate: CrashReportGate) {
        Sentry.init { options ->
            options.dsn = config.dsn
            options.environment = config.environment
            options.release = config.release
            options.dist = config.dist
            // Anonymous by promise (docs/PRIVACY.md): no IP, no user, no cookies.
            options.sendDefaultPii = false
            // The gate is what makes the Settings switch live: it covers
            // captureException *and* the SDK's own uncaught-exception handler.
            options.beforeSend = { event -> if (gate.enabled) event.scrubbed() else null }
            options.beforeBreadcrumb = { breadcrumb -> breadcrumb.scrubbed() }
        }
    }

    override fun capture(throwable: Throwable) {
        Sentry.captureException(throwable)
    }
}

/** Runs [CrashScrubber] over every free-text field an event carries. */
internal fun SentryEvent.scrubbed(): SentryEvent {
    message?.let { m ->
        m.message = CrashScrubber.scrubOrNull(m.message)
        m.formatted = CrashScrubber.scrubOrNull(m.formatted)
    }
    // The SDK hands these over as mutable lists; edit them in place.
    exceptions.indices.forEach { i -> exceptions[i] = exceptions[i].copy(value = CrashScrubber.scrubOrNull(exceptions[i].value)) }
    breadcrumbs.forEach { it.scrubbed() }
    return this
}

internal fun Breadcrumb.scrubbed(): Breadcrumb {
    message = CrashScrubber.scrubOrNull(message)
    getData()?.toMap()?.forEach { (key, value) -> if (value is String) setData(key, CrashScrubber.scrub(value)) }
    return this
}
