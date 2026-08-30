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
 * Auth is anonymous-first ([signInAnonymously]) with an OAuth upgrade
 * ([beginOAuth] / [completeOAuth]) — no backend requires a password, matching
 * this app's "no login required" default (ADR 0002). The email one-time-code
 * flow this interface carried until ADR 0014 is gone: Supabase's free tier
 * refuses to customise the email templates, and its stock ones send a
 * clickable link rather than a typable code, so the flow could not be made to
 * work without paying for a plan or standing up an SMTP provider. See
 * docs/SYNC.md.
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
     * Starts an OAuth sign-in and returns the URL to open in a browser.
     *
     * Two calls rather than one because the user leaves the app in between:
     * the provider's page runs in a browser, and the result comes back as a
     * redirect to [redirectUri] that the platform delivers separately (on
     * Android, an intent into `MainActivity`). Whoever receives that redirect
     * calls [completeOAuth] with its `code` parameter.
     *
     * The implementation holds the PKCE verifier for the attempt, so callers
     * never handle it. One attempt is tracked at a time — starting a second
     * discards the first, which matches what a user tapping the button twice
     * expects.
     */
    suspend fun beginOAuth(provider: OAuthProvider, redirectUri: String): Result<String>

    /** Exchanges the `code` from a [beginOAuth] redirect for a real session. Fails if no attempt is in flight. */
    suspend fun completeOAuth(authCode: String): Result<SyncSession>

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
