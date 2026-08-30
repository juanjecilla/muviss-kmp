package com.codingpit.muviss.core.billing

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementTest {

    @Test
    fun an_unanswered_check_is_not_a_grant() {
        // Unknown is what a store SDK reports before it has reached the
        // network. Reading it as entitled would hand the paid feature to
        // anyone opening the app offline.
        assertFalse(Entitlement.Unknown.isEntitled)
        assertFalse(Entitlement.Inactive.isEntitled)
        assertTrue(Entitlement.Active.isEntitled)
    }

    @Test
    fun the_default_provider_fails_closed() = runTest {
        // Bound whenever no store is configured. The alternative — treating
        // "no billing configured" as "everyone is entitled" — makes a
        // misconfigured release give sync away, and nothing in the build
        // distinguishes that from intent.
        assertEquals(Entitlement.Inactive, NoEntitlementProvider().entitlement.first())
    }

    @Test
    fun the_default_provider_reports_inactive_rather_than_unknown() = runTest {
        // Unknown would leave a paywall spinning forever waiting for an answer
        // that is never coming; Inactive at least renders a truthful state.
        assertEquals(Entitlement.Inactive, NoEntitlementProvider().entitlement.first())
    }

    @Test
    fun the_developer_override_grants_the_entitlement() = runTest {
        assertEquals(Entitlement.Active, AlwaysEntitledProvider().entitlement.first())
    }
}
