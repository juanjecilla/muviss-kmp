package com.codingpit.muviss.di

import com.codingpit.muviss.core.billing.EntitlementProvider
import com.codingpit.muviss.core.billing.EntitlementRecord
import com.codingpit.muviss.core.billing.EntitlementSource
import com.codingpit.muviss.core.sync.EntitlementGate
import com.codingpit.muviss.core.sync.SyncBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Joins `:core:billing` to `:core:sync` — the only place in the app where the
 * two meet.
 *
 * Neither module depends on the other, deliberately (ADR 0018). `:core:sync`
 * declares [EntitlementGate] as a bare `suspend () -> Boolean` so it never
 * learns what a store is, and `:core:billing` publishes an entitlement without
 * knowing what is gated on it. The app shell is where a wiring decision like
 * "sync is the paid feature" belongs, and swapping which features are paid is
 * an edit to this file alone.
 *
 * It joins them in both directions now. Since ADR 0019 the entitlement's
 * source of truth for this app is the Supabase project's mirror of it, which
 * only the sync backend can reach (its session, its token refresh) — so the
 * backend is adapted to billing's [EntitlementSource] here too.
 *
 * This module must come after `syncModule` in [appModules]: both bind
 * [EntitlementGate], and Koin's last binding wins. `syncModule`'s permissive
 * default is what keeps `:core:sync` usable — and testable — on its own.
 */
val billingSyncBridgeModule: Module = module {
    single<EntitlementGate> {
        val provider = get<EntitlementProvider>()
        EntitlementGate { provider.entitlement.first().isEntitled }
    }
    single<EntitlementSource> { SyncBackendEntitlementSource(get()) }
}

/**
 * [EntitlementSource] over the bound [SyncBackend]: the token claim, the
 * server's row and a session refresh, all through the backend's single session.
 */
internal class SyncBackendEntitlementSource(private val backend: SyncBackend) : EntitlementSource {
    /** The user and a hash of the token: enough to change on every replacement without the token itself leaving `:core:sync`'s hands. */
    override val credentials: Flow<String?> = backend.session.map { session -> session?.let { "${it.userId}:${it.accessToken.hashCode()}" } }

    override suspend fun credentialsGrantUntil(): Long? = backend.syncGrantedUntil()

    override suspend fun fetchRecord(): Result<EntitlementRecord?> = backend.fetchEntitlement().map { record -> record?.let { EntitlementRecord(it.active, it.expiresAtEpochMs) } }

    override suspend fun refreshCredentials(): Result<Unit> = backend.refreshSession().map { }
}
