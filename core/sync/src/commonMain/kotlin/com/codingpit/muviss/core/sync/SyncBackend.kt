package com.codingpit.muviss.core.sync

import kotlinx.coroutines.flow.Flow

/**
 * The extensibility seam for cloud sync backends (ADR 0009) — mirrors how
 * [com.codingpit.muviss.core.network.MetadataProvider] abstracts TMDB (ADR
 * 0001). `SupabaseSyncBackend` is the only implementation today; a Firebase
 * (or self-hosted) backend plugs in later by implementing this interface —
 * no change to [SyncEngine] or the UI. Shaped around the change-log
 * ([SyncChangeSet], i.e. dirty local rows / changed remote rows), not any
 * backend's native SDK shape, so [SyncEngine] never has to know which
 * backend it's talking to.
 *
 * Auth is anonymous-first ([signInAnonymously]) with an optional email
 * one-time-code upgrade ([requestEmailOtp] / [verifyEmailOtp]) — no backend
 * requires a password, matching this app's "no login required" default
 * (ADR 0002). See docs/SYNC.md for the concrete Supabase project setup.
 */
interface SyncBackend {
    val id: SyncBackendId

    /**
     * The current session, or null when signed out. Replays its latest
     * value to new subscribers so callers (e.g. [SyncEngine], the profile
     * screen) can read the current state synchronously via `.first()`
     * without racing startup session restoration.
     */
    val session: Flow<SyncSession?>

    /** Starts (or resumes) an anonymous session — no email required, the default entry point. */
    suspend fun signInAnonymously(): Result<SyncSession>

    /**
     * Sends a one-time login code to [email]. Call [verifyEmailOtp] with the
     * code the user receives to complete sign-in — upgrades an existing
     * anonymous session in place when one is active, otherwise starts a new
     * session once verified.
     */
    suspend fun requestEmailOtp(email: String): Result<Unit>

    /** Completes a [requestEmailOtp] challenge. */
    suspend fun verifyEmailOtp(email: String, code: String): Result<SyncSession>

    suspend fun signOut()

    /**
     * Pushes local [changes] to the backend. Implementations upsert per
     * row; server-side last-write-wins (on each row's own
     * `updated_at_epoch_ms`) protects against a stale push clobbering a
     * newer remote write that arrived from another device in between — see
     * docs/SYNC.md's schema for how Supabase enforces this without any
     * client-side coordination.
     */
    suspend fun push(changes: SyncChangeSet): Result<Unit>

    /** Pulls every remote row changed after [sinceEpochMs] (null = full pull, e.g. first sync on a fresh install). */
    suspend fun pull(sinceEpochMs: Long?): Result<SyncChangeSet>
}
