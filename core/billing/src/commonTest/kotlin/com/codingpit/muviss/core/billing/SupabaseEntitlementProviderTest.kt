package com.codingpit.muviss.core.billing

import app.cash.turbine.test
import com.codingpit.muviss.core.common.AppClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupabaseEntitlementProviderTest {

    private class FixedClock(var millis: Long = NOW) : AppClock {
        override fun nowEpochMs(): Long = millis
    }

    /** What the sync backend would answer, scripted. [log] records the order of the calls that cost something. */
    private class FakeSource : EntitlementSource {
        override val credentials = MutableStateFlow<String?>("user-1:token-1")
        var grantUntil: Long? = null
        var grantAfterRefresh: Long? = null
        val records = ArrayDeque<Result<EntitlementRecord?>>()
        var lastRecord: Result<EntitlementRecord?> = Result.success(null)
        var refreshFailure: Throwable? = null
        val log = mutableListOf<String>()

        override suspend fun credentialsGrantUntil(): Long? = grantUntil

        override suspend fun fetchRecord(): Result<EntitlementRecord?> {
            log += "fetch"
            return records.removeFirstOrNull()?.also { lastRecord = it } ?: lastRecord
        }

        override suspend fun refreshCredentials(): Result<Unit> {
            log += "refresh"
            refreshFailure?.let { return Result.failure(it) }
            grantAfterRefresh?.let { grantUntil = it }
            credentials.value = "user-1:token-${log.size}"
            return Result.success(Unit)
        }
    }

    private val source = FakeSource()
    private val clock = FixedClock()
    private val provider = SupabaseEntitlementProvider(source, clock)

    private suspend fun current(): Entitlement = provider.entitlement.first()

    @Test
    fun signed_out_is_inactive() = runTest {
        source.credentials.value = null

        assertEquals(Entitlement.Inactive, current())
        assertTrue(source.log.isEmpty())
    }

    @Test
    fun a_live_claim_is_active_without_a_request() = runTest {
        source.grantUntil = NOW + 1

        assertEquals(Entitlement.Active, current())
        assertTrue(source.log.isEmpty(), "the fast path costs nothing")
    }

    @Test
    fun a_claim_in_the_past_falls_back_to_the_row() = runTest {
        source.grantUntil = NOW
        source.lastRecord = Result.success(EntitlementRecord(active = false, expiresAtEpochMs = null))

        assertEquals(Entitlement.Inactive, current())
        assertEquals(listOf("fetch"), source.log)
    }

    @Test
    fun an_active_row_with_no_end_is_active_even_before_the_token_carries_it() = runTest {
        source.lastRecord = Result.success(EntitlementRecord(active = true, expiresAtEpochMs = null))

        assertEquals(Entitlement.Active, current())
    }

    @Test
    fun an_active_row_that_ends_later_is_active_and_one_that_has_ended_is_not() = runTest {
        source.lastRecord = Result.success(EntitlementRecord(active = true, expiresAtEpochMs = NOW + 1))
        assertEquals(Entitlement.Active, current())

        source.lastRecord = Result.success(EntitlementRecord(active = true, expiresAtEpochMs = NOW))
        assertEquals(Entitlement.Inactive, current())
    }

    @Test
    fun no_row_is_inactive() = runTest {
        assertEquals(Entitlement.Inactive, current())
    }

    @Test
    fun a_read_that_fails_is_unknown_which_is_not_a_grant() = runTest {
        source.lastRecord = Result.failure(IllegalStateException("offline"))

        val entitlement = current()

        assertEquals(Entitlement.Unknown, entitlement)
        assertEquals(false, entitlement.isEntitled)
    }

    @Test
    fun new_credentials_are_read_again() = runTest {
        provider.entitlement.test {
            assertEquals(Entitlement.Inactive, awaitItem())
            source.grantUntil = NOW + 1
            source.credentials.value = "user-1:token-2"
            assertEquals(Entitlement.Active, awaitItem())
            source.credentials.value = null
            assertEquals(Entitlement.Inactive, awaitItem())
        }
    }

    @Test
    fun refresh_reads_again_for_a_live_collector() = runTest {
        provider.entitlement.test {
            assertEquals(Entitlement.Inactive, awaitItem())
            source.lastRecord = Result.success(EntitlementRecord(active = true, expiresAtEpochMs = null))
            provider.refresh()
            assertEquals(Entitlement.Active, awaitItem())
        }
    }

    // --- After a purchase ----------------------------------------------------

    @Test
    fun after_a_purchase_the_row_is_polled_until_active_and_only_then_the_credentials_are_renewed() = runTest {
        source.records += Result.success(null)
        source.records += Result.failure(IllegalStateException("blip"))
        source.records += Result.success(EntitlementRecord(active = true, expiresAtEpochMs = null))
        source.grantAfterRefresh = Long.MAX_VALUE

        val result = provider.refreshAfterPurchase()

        assertEquals(Entitlement.Active, result)
        assertEquals(listOf("fetch", "fetch", "fetch", "refresh"), source.log, "renewing before the webhook lands mints a token with no claim")
        assertEquals(1_000L + 2_000L, currentTime, "backs off between reads")
        assertEquals(Entitlement.Active, current())
    }

    @Test
    fun a_webhook_that_never_lands_gives_up_within_the_budget_without_renewing() = runTest {
        val result = provider.refreshAfterPurchase()

        assertEquals(Entitlement.Unknown, result, "the purchase is real; it has not arrived yet")
        assertEquals(PurchasePolling().maxAttempts, source.log.count { it == "fetch" })
        assertTrue("refresh" !in source.log)
        assertEquals(1_000L + 2_000L + 4_000L + 8_000L * 4, currentTime, "bounded: about forty seconds")
    }

    @Test
    fun a_renewal_without_a_claim_is_unknown_not_active() = runTest {
        source.lastRecord = Result.success(EntitlementRecord(active = true, expiresAtEpochMs = null))
        // The hook is off, or lagging: the row says yes and the new token says nothing.

        assertEquals(Entitlement.Unknown, provider.refreshAfterPurchase())
    }

    @Test
    fun a_renewal_that_fails_is_unknown() = runTest {
        source.lastRecord = Result.success(EntitlementRecord(active = true, expiresAtEpochMs = null))
        source.refreshFailure = IllegalStateException("offline")

        assertEquals(Entitlement.Unknown, provider.refreshAfterPurchase())
    }

    @Test
    fun the_no_store_providers_answer_a_purchase_with_what_they_always_say() = runTest {
        assertEquals(Entitlement.Inactive, NoEntitlementProvider().refreshAfterPurchase())
        assertEquals(Entitlement.Active, AlwaysEntitledProvider().refreshAfterPurchase())
    }

    private companion object {
        const val NOW = 1_000_000L
    }
}
