package com.codingpit.muviss.core.common.crash

/**
 * No-op on the Wasm target: the web app doesn't depend on the Sentry SDK at
 * all (see `:core:common`'s build.gradle.kts — the dependency is only added
 * to android/iOS/jvm source sets). Revisit if/when web ships crash
 * reporting.
 */
actual object CrashReporter {
    actual fun init(dsn: String) = Unit

    actual fun recordException(throwable: Throwable) = Unit
}
