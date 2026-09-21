package com.codingpit.muviss.sync

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Desktop's counterpart to Android's `SyncWorker` and iOS's background refresh
 * (EPIC 40, ADR 0021): a timer that asks for a `Periodic` sync every
 * [INTERVAL], and **only while the window is open**. Nothing here can run with
 * the app closed — a login item or launchd/Task Scheduler entry is the real fix
 * and is not part of this epic — and the Profile copy for this platform says so.
 *
 * Waits first rather than syncing at once: opening the app is already a
 * foreground, which `SyncCoordinator.onForeground` handles. [tick] is
 * `SyncCoordinator.runPeriodic`, so whether it does anything is decided by the
 * engine (switch, build flag, session, entitlement), not here.
 */
class DesktopAutoSync(private val interval: Duration = INTERVAL, private val tick: suspend () -> Unit) {

    /** Never returns; cancel it by leaving the composition it was launched from. */
    suspend fun run() {
        while (true) {
            delay(interval)
            // A failed tick must not end the timer: the outcome is recorded on
            // the engine's `syncState`, and the next tick tries again.
            runCatching { tick() }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }
    }

    companion object {
        val INTERVAL: Duration = 15.minutes
    }
}
