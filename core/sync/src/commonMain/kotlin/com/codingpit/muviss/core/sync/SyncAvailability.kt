package com.codingpit.muviss.core.sync

/**
 * Whether this build ships sync at all: `SYNC_ENABLED` set *and* Supabase
 * project keys present. Bound from [MuvissBuildConfig] in `di/SyncModule.kt`;
 * the profile screen reads this to hide the sync entry point gracefully when
 * it isn't — the same "blank key = feature quietly absent" contract as
 * `CrashReporter`'s Sentry DSN (see CLAUDE.md, ADR 0007).
 *
 * Distinct from [EntitlementGate], and the two are not interchangeable. This
 * one is decided at build time and answers "does this app have the feature";
 * an unavailable build renders no sync UI whatsoever. The gate is decided at
 * runtime and answers "has this user paid for it"; a gated build shows the
 * feature and a way to buy it. See ADR 0018.
 */
fun interface SyncAvailability {
    fun isConfigured(): Boolean

    /**
     * Whether this build may sync without being asked: `SYNC_BACKGROUND_ENABLED`
     * set *on top of* [isConfigured] (EPIC 40, ADR 0021). The first of the three
     * layers that gate automatic sync; when false the Profile screen renders no
     * switch at all, the same rule [isConfigured] follows. False unless an
     * implementation says otherwise, so a test double written as a lambda can
     * never turn background sync on by accident.
     */
    fun isBackgroundAvailable(): Boolean = false
}
