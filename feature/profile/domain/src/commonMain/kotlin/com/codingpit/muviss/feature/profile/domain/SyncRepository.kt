package com.codingpit.muviss.feature.profile.domain

import kotlinx.coroutines.flow.Flow

/**
 * Domain-owned contract for the sync feature (EPIC 9). The implementation
 * (`feature/profile/data`'s `CoreSyncRepository`) delegates to `:core:sync`'s
 * `SyncBackend`/`SyncEngine`, kept behind this interface for the same reason
 * every other repository in this codebase hides its data-layer
 * implementation from the domain contract (ADR 0004).
 *
 * Auth here is the email one-time-code flow only ([requestSignInCode] /
 * [verifySignInCode]) — see [SyncAccountState.SignedIn]'s KDoc for why
 * anonymous sign-in isn't exposed at this layer.
 */
interface SyncRepository {
    /** False when no Supabase keys are configured for this build — the UI should hide the entry point entirely rather than call anything else here. */
    val isAvailable: Boolean

    fun observeAccount(): Flow<SyncAccountState>

    fun observeLastSyncedAt(): Flow<Long?>

    /** Sends a one-time login code to [email]. */
    suspend fun requestSignInCode(email: String): Result<Unit>

    /** Completes sign-in with the code sent by [requestSignInCode]. */
    suspend fun verifySignInCode(email: String, code: String): Result<Unit>

    suspend fun signOut()

    /** Runs one push+pull cycle. */
    suspend fun syncNow(): SyncOutcomeSummary
}
