package com.codingpit.muviss.core.sync

/**
 * Whether a real [SyncBackend] is configured (Supabase project keys
 * present). Bound from [MuvissBuildConfig] in `di/SyncModule.kt`; the
 * profile screen reads this to hide the sync entry point gracefully when it
 * isn't — the same "blank key = feature quietly absent" contract as
 * `CrashReporter`'s Sentry DSN (see CLAUDE.md, ADR 0007).
 */
fun interface SyncAvailability {
    fun isConfigured(): Boolean
}
