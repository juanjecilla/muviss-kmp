package com.codingpit.muviss.core.billing

import com.codingpit.muviss.core.common.AppClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.coroutines.cancellation.CancellationException

/**
 * The entitlement as the Supabase project knows it (ADR 0019): the mirror of
 * the store's answer that the RevenueCat webhook writes into
 * `public.entitlement`, and the `sync_until` claim the token hook stamps from
 * it. This is what lets a device that never sells — desktop — know that its
 * user paid on a phone, and it is the same answer the server's own policies
 * enforce, so the client gate and the server gate cannot disagree for long.
 *
 * Reached through an [EntitlementSource] the app shell builds over the sync
 * backend, never over a client of its own; see that interface for why.
 *
 * **Reading it.** The token's claim first, because it costs nothing: live
 * credentials that carry a live claim are [Entitlement.Active] without a
 * request. Otherwise the server's row decides — active, and no end or an end
 * in the future. A row that says yes while the credentials say no is a grant
 * the credentials predate (a purchase, or a renewal on another device); that
 * still reads [Entitlement.Active], and the sync engine renews the credentials
 * before it pushes. A read that fails is [Entitlement.Unknown]: not a grant
 * (fail closed), and not a "no" either.
 *
 * Signed out is [Entitlement.Inactive]: there is no account to hold a grant.
 */
class SupabaseEntitlementProvider(
    private val source: EntitlementSource,
    private val clock: AppClock,
    private val purchasePolling: PurchasePolling = PurchasePolling(),
) : EntitlementProvider {

    private val rechecks = MutableStateFlow(0)

    override val entitlement: Flow<Entitlement> = combine(source.credentials.distinctUntilChanged(), rechecks) { credentials, _ -> credentials }
        .map { credentials -> if (credentials == null) Entitlement.Inactive else evaluate() }
        .distinctUntilChanged()

    /** Re-reads, for every collector, on demand (the paywall opening). */
    override suspend fun refresh() {
        rechecks.update { it + 1 }
    }

    /**
     * After a store purchase: polls the server's row, backing off, until it
     * turns active — and **only then** renews the credentials, so the new token
     * carries `sync_until`. Renewing first would mint a token before the
     * webhook has landed, which carries no claim and stays that way for an
     * hour (ADR 0019).
     *
     * [Entitlement.Active] once the renewed credentials carry a live claim;
     * [Entitlement.Unknown] if the row never turned active within
     * [PurchasePolling]'s budget, or the renewal did not produce a claim — the
     * purchase is real and will arrive, it just has not yet; and
     * [Entitlement.Inactive] only when there is no account to wait for.
     */
    override suspend fun refreshAfterPurchase(): Entitlement {
        var wait = purchasePolling.initialDelayMs
        repeat(purchasePolling.maxAttempts) { attempt ->
            if (attempt > 0) {
                delay(wait)
                wait = minOf(wait * 2, purchasePolling.maxDelayMs)
            }
            val record = source.fetchRecord().getOrElse { failure ->
                if (failure is CancellationException) throw failure
                null
            }
            if (record?.isActiveAt(clock.nowEpochMs()) == true) return renewAndRead()
        }
        return Entitlement.Unknown
    }

    private suspend fun renewAndRead(): Entitlement {
        val renewed = source.refreshCredentials()
        renewed.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        refresh()
        return if (renewed.isSuccess && grantIsLive()) Entitlement.Active else Entitlement.Unknown
    }

    private suspend fun evaluate(): Entitlement {
        if (grantIsLive()) return Entitlement.Active
        val record = source.fetchRecord().getOrElse { failure ->
            if (failure is CancellationException) throw failure
            return Entitlement.Unknown
        }
        return if (record?.isActiveAt(clock.nowEpochMs()) == true) Entitlement.Active else Entitlement.Inactive
    }

    private suspend fun grantIsLive(): Boolean = source.credentialsGrantUntil()?.let { it > clock.nowEpochMs() } ?: false
}

/**
 * How long [SupabaseEntitlementProvider.refreshAfterPurchase] waits for the
 * webhook. The defaults read the row 8 times, waiting 1, 2, 4, 8, 8, 8 and 8 s
 * in between — about 40 s in all, which covers RevenueCat's usual delivery with room to spare, without a
 * paywall spinning for minutes over a delivery that has failed.
 */
data class PurchasePolling(
    val initialDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 8_000L,
    val maxAttempts: Int = 8,
)
