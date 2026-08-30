package com.codingpit.muviss.feature.profile.domain

import kotlinx.coroutines.flow.Flow

/**
 * Domain-owned contract for the sync feature (EPIC 9). The implementation
 * (`feature/profile/data`'s `CoreSyncRepository`) delegates to `:core:sync`'s
 * `SyncBackend`/`SyncEngine`, kept behind this interface for the same reason
 * every other repository in this codebase hides its data-layer
 * implementation from the domain contract (ADR 0004).
 *
 * Auth here is OAuth only ([beginSignIn] / [completeSignIn]) — see
 * [SyncAccountState.SignedIn]'s KDoc for why anonymous sign-in isn't exposed
 * at this layer, and ADR 0014 for why the email one-time-code flow this
 * replaced could not be made to work.
 */
interface SyncRepository {
    /** False when no Supabase keys are configured for this build — the UI should hide the entry point entirely rather than call anything else here. */
    val isAvailable: Boolean

    fun observeAccount(): Flow<SyncAccountState>

    fun observeLastSyncedAt(): Flow<Long?>

    /**
     * Starts sign-in and returns the URL the caller must open in a browser.
     *
     * The domain deliberately stops at "here is a URL": opening it is a
     * platform concern (a browser on Android, nothing at all on the targets
     * that cannot come back), and the result arrives out-of-band as a
     * redirect rather than as this call's return value.
     */
    suspend fun beginSignIn(provider: SyncProvider): Result<String>

    /** Completes sign-in from the `code` carried by a [beginSignIn] redirect. */
    suspend fun completeSignIn(authCode: String): Result<Unit>

    /**
     * Why the last sign-in attempt failed, or null.
     *
     * A stream rather than [completeSignIn]'s return value because the two can
     * happen in different places: the redirect is redeemed at app scope, which
     * may be while no profile screen exists (ADR 0014). Cleared through
     * [signInFailureShown] once displayed.
     */
    fun observeSignInFailure(): Flow<String?>

    fun signInFailureShown()

    suspend fun signOut()

    /** Runs one push+pull cycle. */
    suspend fun syncNow(): SyncOutcomeSummary
}
