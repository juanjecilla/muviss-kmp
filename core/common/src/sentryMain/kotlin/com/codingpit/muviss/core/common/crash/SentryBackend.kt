package com.codingpit.muviss.core.common.crash

import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb

/**
 * `platformCrashBackend()`'s `actual`, declared here **once** rather than once
 * per platform (#109): `sentryMain` (see `core/common/build.gradle.kts`) sits
 * in the `dependsOn` closure of android, jvm *and* ios, so a single `actual` in
 * an intermediate source set satisfies all three — the same pattern this
 * project's default hierarchy template already uses for `appleMain` hosting
 * one `actual` for ios/macos/watchos/tvos. (An `actual` per platform file was
 * tried first; the Kotlin/Native metadata compile for the intermediate
 * `iosMain` source set only sees its own `dependsOn` chain, which excluded
 * `sentryMain`, and failed with "Unresolved reference" — moving the `actual`
 * itself into `sentryMain` sidesteps that rather than fighting it.)
 */
internal actual fun platformCrashBackend(): CrashBackend = SentryBackend

/**
 * The one copy of the Sentry [CrashBackend], shared by android/jvm/ios so this
 * file — and the [scrubbed] mapping it drives — is exercised by
 * [SentryEventScrubbingTest] on every platform that ships it, not
 * compile-checked only on two of the three (#109).
 */
internal object SentryBackend : CrashBackend {
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
            // Release-health "session" envelopes bypass beforeSend entirely
            // (they are not events), so the opt-out above cannot stop them —
            // that gap is #125. Nothing in the app reads a crash-free-session
            // rate and there is no Sentry project measuring one, so rather than
            // ship a heartbeat the "Send crash reports" switch cannot silence,
            // this turns the integration off outright. Revisit only alongside
            // wiring up release health for real, and note it stays init-time
            // either way — unlike [gate], it cannot be flipped live.
            options.enableAutoSessionTracking = false
        }
    }

    override fun capture(throwable: Throwable) {
        Sentry.captureException(throwable)
    }
}

/**
 * Runs [CrashScrubber] over every free-text field an event carries — message,
 * exception values, tags, contexts and breadcrumbs.
 *
 * This SDK's [SentryEvent] does not expose `request` or `extra` at all (checked
 * against `sentry-kotlin-multiplatform` 0.27.0's generated JVM class: neither
 * field exists on `SentryEvent`/`SentryBaseEvent`), so there is nothing to scrub
 * there — #126 found the old doc comment claiming a wider reach than the code
 * had; this one is scoped to what the type actually carries. `exceptions[].type`
 * is the exception's class name, not free text, so it is left alone.
 */
internal fun SentryEvent.scrubbed(): SentryEvent {
    message?.let { m ->
        m.message = CrashScrubber.scrubOrNull(m.message)
        m.formatted = CrashScrubber.scrubOrNull(m.formatted)
    }
    // The SDK hands these over as mutable lists; edit them in place.
    exceptions.indices.forEach { i -> exceptions[i] = exceptions[i].copy(value = CrashScrubber.scrubOrNull(exceptions[i].value)) }
    breadcrumbs.forEach { it.scrubbed() }
    // tags/contexts are exposed as immutable `Map`s with a setter, not a
    // mutable map to edit in place — reassign rather than mutate.
    tags = tags.mapValues { (_, value) -> CrashScrubber.scrub(value) }.toMutableMap()
    contexts = contexts.mapValues { (_, value) -> if (value is String) CrashScrubber.scrub(value) else value }.toMutableMap()
    return this
}

internal fun Breadcrumb.scrubbed(): Breadcrumb {
    message = CrashScrubber.scrubOrNull(message)
    getData()?.toMap()?.forEach { (key, value) -> if (value is String) setData(key, CrashScrubber.scrub(value)) }
    return this
}
