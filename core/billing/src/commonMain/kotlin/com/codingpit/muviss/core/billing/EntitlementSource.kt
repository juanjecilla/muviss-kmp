package com.codingpit.muviss.core.billing

import kotlinx.coroutines.flow.Flow

/**
 * What [SupabaseEntitlementProvider] needs from the account the user is signed
 * in to, and nothing more.
 *
 * A seam rather than a dependency because `:core:billing` must not depend on
 * `:core:sync` (ADR 0018): the server's mirror of the entitlement is reached
 * through the sync backend's own HTTP client and session — the one that
 * refreshes tokens under a lock, since GoTrue rotates refresh tokens and a
 * second refresher would invalidate the first — so the app shell adapts that
 * backend to this interface (`di/BillingSyncBridge.kt`) and this module never
 * learns that a sync backend exists.
 */
interface EntitlementSource {
    /**
     * An opaque marker of the current credentials: null when signed out, and a
     * new value whenever they are replaced (sign-in, sign-out, a refresh). The
     * provider re-reads the entitlement on every change, because new
     * credentials can carry a new grant.
     */
    val credentials: Flow<String?>

    /**
     * Until when the current credentials grant the entitlement, as the server
     * will judge them (epoch ms), or null for no grant. Local and cheap: the
     * fast path that answers without a request.
     */
    suspend fun credentialsGrantUntil(): Long?

    /** The server's record for this account, or null when it has none. Costs a request. */
    suspend fun fetchRecord(): Result<EntitlementRecord?>

    /** Replaces the credentials, so they carry the grant the server holds *now*. */
    suspend fun refreshCredentials(): Result<Unit>
}

/** The server's mirror of the store's answer (ADR 0019): granted at all, and until when — null for a grant with no end. */
data class EntitlementRecord(val active: Boolean, val expiresAtEpochMs: Long?) {
    fun isActiveAt(nowEpochMs: Long): Boolean = active && (expiresAtEpochMs == null || expiresAtEpochMs > nowEpochMs)
}
