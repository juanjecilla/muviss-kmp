package com.codingpit.muviss.core.sync

/**
 * Identifies which [SyncBackend] implementation a [SyncSession] belongs to.
 * Stored alongside the session (`syncAccount.backendId`, see
 * `SyncAccount.sq`) so a cached session is never silently reused against a
 * different backend after a swap — see ADR 0009's multi-backend seam.
 */
enum class SyncBackendId {
    SUPABASE,

    /** [NoOpSyncBackend]'s id — never actually persisted, since [NoOpSyncBackend] never produces a session. */
    NONE,
}

/**
 * An authenticated sync session, backend-agnostic (ADR 0009). [email] is
 * null for an anonymous session (see [SyncBackend.signInAnonymously]) —
 * anonymous-first is the default entry point, upgraded in place to a real
 * email identity via [SyncBackend.verifyEmailOtp] without losing the
 * session's [userId] or anything already pushed under it.
 */
data class SyncSession(
    val backendId: SyncBackendId,
    val userId: String,
    val email: String?,
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long?,
)
