package com.codingpit.muviss.core.common.crash

/**
 * Seam for crash/error reporting (Sentry Kotlin Multiplatform SDK on
 * Android/iOS/JVM). [init] is a no-op when [dsn] is blank — the default for
 * local/dev builds and CI, which never see `SENTRY_DSN` — so building and
 * running the app never requires a Sentry project. It is also a no-op on web
 * targets (JS/Wasm), which don't depend on the Sentry SDK at all; see the
 * `js`/`wasmJs` actuals.
 */
expect object CrashReporter {
    fun init(dsn: String)

    fun recordException(throwable: Throwable)
}
