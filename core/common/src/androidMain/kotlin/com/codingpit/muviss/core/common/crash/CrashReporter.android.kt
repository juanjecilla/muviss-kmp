package com.codingpit.muviss.core.common.crash

import io.sentry.kotlin.multiplatform.Sentry

actual object CrashReporter {
    actual fun init(dsn: String) {
        if (dsn.isBlank()) return
        Sentry.init { options -> options.dsn = dsn }
    }

    actual fun recordException(throwable: Throwable) {
        Sentry.captureException(throwable)
    }
}
