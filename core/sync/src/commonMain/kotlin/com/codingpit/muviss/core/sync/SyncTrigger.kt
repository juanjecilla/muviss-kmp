package com.codingpit.muviss.core.sync

/**
 * Why a sync cycle is being asked for (EPIC 40, ADR 0021).
 *
 * The distinction that matters is [isAutomatic]: [Manual] is a person pressing
 * "Sync now" (or completing sign-in), and always runs. Everything else is the
 * app deciding to sync on its own, and runs only when [AutoSyncPolicy] says the
 * build and the user both allowed it.
 */
enum class SyncTrigger(val isAutomatic: Boolean) {
    /** The user asked. Never gated by the automatic-sync switch. */
    Manual(isAutomatic = false),

    /** The app came to the foreground (`ON_START` / `ON_RESUME`). */
    Foreground(isAutomatic = true),

    /** Local writes are waiting to be sent — the debounced push. */
    Change(isAutomatic = true),

    /** An OS or timer tick: WorkManager, `BGAppRefreshTask`, the desktop timer. */
    Periodic(isAutomatic = true),

    /** A web tab became visible again, or the browser came back online. */
    Resume(isAutomatic = true),
}
