package com.codingpit.muviss.core.common.widget

/**
 * Tells the platform's home-screen widgets that what they are showing has
 * changed (EPIC 22).
 *
 * A widget renders in a surface the app does not own and cannot recompose: an
 * Android `AppWidgetHost` process, or an iOS widget extension. Neither
 * observes a `Flow`. Something has to push, and the honest place to push from
 * is the write itself — [com.codingpit.muviss.core.common.AppClock]-style, a
 * seam rather than a direct call, so `:core:common` never learns what Glance
 * or WidgetKit are.
 *
 * Implementations must be cheap and must not throw: a failed widget refresh
 * is a stale row on a home screen, and it may not become a failed tick.
 */
fun interface WidgetRefresher {
    suspend fun refresh()
}

/** The implementation on every target with no home screen to speak of — desktop and web. */
object NoOpWidgetRefresher : WidgetRefresher {
    override suspend fun refresh() = Unit
}

/**
 * The process-wide refresher, delegating to whatever the platform host
 * installed at startup.
 *
 * The indirection exists because of *when* things happen rather than where.
 * The Glance refresher needs an Android `Context`, which only
 * `MuvissApplication.onCreate` has, and iOS's needs nothing until
 * `IosAppStartup.start()` runs — both later than the Koin graph that has
 * already handed a [WidgetRefresher] to the repository. Resolving the
 * delegate per call rather than per binding makes that ordering irrelevant,
 * and leaves the four other targets on [NoOpWidgetRefresher] with nothing to
 * configure.
 *
 * This is the same shape as
 * [CrashReporter][com.codingpit.muviss.core.common.crash.CrashReporter]: one
 * seam, configured once at startup by the host that knows the platform.
 */
object AppWidgets : WidgetRefresher {

    private var installed: WidgetRefresher = NoOpWidgetRefresher

    /** Called once by a platform host at startup. Later calls replace the previous refresher. */
    fun install(refresher: WidgetRefresher) {
        installed = refresher
    }

    /** Restores the no-op — for tests, and for a host tearing down. */
    fun reset() {
        installed = NoOpWidgetRefresher
    }

    override suspend fun refresh() = installed.refresh()
}
