package com.codingpit.muviss.core.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The backend bound when no Supabase keys are configured (`di/SyncModule.kt`
 * checks [SyncAvailability] before choosing this over `SupabaseSyncBackend`)
 * — sync stays entirely optional and inert rather than the app crashing or
 * every call failing loudly. Auth calls fail with a clear message (the
 * profile UI is expected to hide the sign-in entry point entirely via
 * [SyncAvailability] and never actually call these); [push]/[pull] succeed
 * as true no-ops so anything that accidentally calls [SyncEngine] with this
 * backend wired in (e.g. a test, or [SyncEngine]'s own foreground
 * best-effort trigger before a session exists) degrades silently instead of
 * throwing.
 */
class NoOpSyncBackend : SyncBackend {
    override val id: SyncBackendId = SyncBackendId.NONE

    private val sessionState = MutableStateFlow<SyncSession?>(null)
    override val session: StateFlow<SyncSession?> = sessionState

    override suspend fun signInAnonymously(): Result<SyncSession> = Result.failure(notConfigured())

    override suspend fun beginOAuth(provider: OAuthProvider, redirectUri: String): Result<String> = Result.failure(notConfigured())

    override suspend fun completeOAuth(authCode: String): Result<SyncSession> = Result.failure(notConfigured())

    override suspend fun signOut() = Unit

    override suspend fun push(changes: SyncChangeSet): Result<Unit> = Result.success(Unit)

    override suspend fun pull(after: Map<SyncTable, SyncCursor>, onPage: suspend (SyncPage) -> Unit): Result<Unit> = Result.success(Unit)

    private fun notConfigured(): IllegalStateException = IllegalStateException(
        "Sync is not configured: SYNC_ENABLED and SUPABASE_URL/SUPABASE_ANON_KEY must all be set in local.properties (see docs/SYNC.md).",
    )
}
