package com.codingpit.muviss.feature.profile.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncPresentationTest {

    @Test
    fun a_quiet_status_has_nothing_to_add() {
        assertNull(syncStatusDetail(SyncStatus(lastSyncedAtEpochMs = 1L)))
    }

    @Test
    fun unsent_changes_are_reported_with_their_count() {
        val detail = syncStatusDetail(SyncStatus(pendingChanges = 4))

        assertEquals(SyncStatusDetail.Waiting(4), detail)
        assertEquals("4 changes waiting", SyncCopy.detail(detail!!))
    }

    @Test
    fun one_change_is_singular() {
        assertEquals("1 change waiting", SyncCopy.detail(SyncStatusDetail.Waiting(1)))
    }

    @Test
    fun a_failure_outranks_waiting_changes() {
        val detail = syncStatusDetail(SyncStatus(pendingChanges = 4, lastFailure = SyncFailureKind.Offline))

        assertEquals(SyncStatusDetail.Failed(SyncFailureKind.Offline), detail)
        assertEquals("Last sync failed: you seem to be offline", SyncCopy.detail(detail!!))
    }

    @Test
    fun an_account_mismatch_outranks_everything() {
        val detail = syncStatusDetail(SyncStatus(pendingChanges = 4, lastFailure = SyncFailureKind.Server, accountChanged = true))

        assertEquals(SyncStatusDetail.AccountChanged, detail)
        assertTrue(SyncCopy.detail(detail!!).contains("different account"))
    }

    @Test
    fun every_failure_kind_has_its_own_copy_and_none_leaks_technical_text() {
        val copies = SyncFailureKind.entries.map { SyncCopy.failure(it) }

        assertEquals(copies.size, copies.toSet().size, "each reason reads differently")
        copies.forEach { copy ->
            listOf("HTTP", "Exception", "Supabase", "401", "500").forEach { assertTrue(!copy.contains(it), "\"$copy\" mentions $it") }
        }
    }

    @Test
    fun each_platform_gets_a_description_that_does_not_overpromise() {
        val inBackground = SyncCopy.automaticSyncDescription(AutomaticSyncMode.InBackground)
        val system = SyncCopy.automaticSyncDescription(AutomaticSyncMode.WhenSystemAllows)
        val open = SyncCopy.automaticSyncDescription(AutomaticSyncMode.WhileOpen)

        assertTrue(inBackground.contains("background"))
        assertTrue(system.contains("system allows"))
        assertTrue(open.contains("while Muviss is open", ignoreCase = true))
        assertEquals(3, setOf(inBackground, system, open).size)
    }
}
