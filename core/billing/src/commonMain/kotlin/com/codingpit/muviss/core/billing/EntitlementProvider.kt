package com.codingpit.muviss.core.billing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The source of truth for whether the user has paid, and the only thing the
 * rest of the app learns about billing.
 *
 * A [Flow] rather than a suspend getter because a subscription changes
 * underneath the app — it lapses, it renews, a purchase completes on another
 * device, a refund lands — and every one of those has to reach the UI without
 * a restart. That is also why the entitlement is not resolved once into a Koin
 * `single`: a value read at graph-build time would strand a user who has just
 * bought the thing.
 *
 * No RevenueCat here, and none in `commonMain` at all: `purchases-kmp`
 * publishes Android and iOS artifacts only, so a `commonMain` dependency on it
 * breaks `compileKotlinWasmJs` for every downstream module — the same class of
 * failure CLAUDE.md documents for `:models`' serialization scoping. The vendor
 * belongs in `androidMain`/`iosMain` implementations of this interface.
 */
interface EntitlementProvider {
    val entitlement: Flow<Entitlement>

    /** Re-checks with the store. Called when the user opens the paywall or returns from a purchase; a no-op for implementations with nothing to ask. */
    suspend fun refresh()

    /**
     * Called once a store reports a completed purchase: waits — bounded — until
     * the purchase has reached everything the entitlement depends on, and
     * returns what [entitlement] reads afterwards. [Entitlement.Unknown] means
     * it has not arrived yet, which a paywall shows as "processing" rather than
     * as a failure.
     *
     * The default suits an implementation whose only source is the store
     * itself. [SupabaseEntitlementProvider] overrides it, because there a
     * purchase lands on the server *after* the store says it is done (ADR 0019).
     */
    suspend fun refreshAfterPurchase(): Entitlement {
        refresh()
        return entitlement.first()
    }
}

/**
 * The default binding: nobody is entitled, ever.
 *
 * Bound whenever no store implementation is configured, which today is every
 * build (see `di/BillingModule.kt`). Reporting [Entitlement.Inactive] rather
 * than [Entitlement.Unknown] matters — Unknown would leave the paywall
 * spinning forever, while Inactive renders a purchase button that a build with
 * no store cannot honour but which at least tells the truth about the state.
 */
class NoEntitlementProvider : EntitlementProvider {
    override val entitlement: Flow<Entitlement> = MutableStateFlow(Entitlement.Inactive).asStateFlow()

    override suspend fun refresh() = Unit
}

/**
 * Grants the entitlement unconditionally, bound only when
 * `SYNC_ENTITLEMENT_OVERRIDE` is set in `local.properties` (ADR 0018).
 *
 * This exists so sync can be dogfooded before any store exists. It is a build
 * input, never a runtime one, and it is absent from CI and release builds — so
 * it cannot be flipped on by a user, only by whoever compiled the app.
 */
class AlwaysEntitledProvider : EntitlementProvider {
    override val entitlement: Flow<Entitlement> = MutableStateFlow(Entitlement.Active).asStateFlow()

    override suspend fun refresh() = Unit
}
