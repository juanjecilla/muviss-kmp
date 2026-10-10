package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.testing.FakeSupabaseServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client half of ADR 0019's server gate (#279), run as the real backend
 * and engine against a [FakeSupabaseServer] that models it: every synced table
 * wants a live `sync_until` claim, refuses a push without one with 403 /
 * `42501`, and answers a pull without one with a silent `200 []`.
 *
 * The engine's own [EntitlementGate] is the permissive default here — these
 * are about what happens when the client gate says yes and the *server* says
 * no, which is the case that used to be reported as a failure (or worse, as a
 * successful sync that saw nothing).
 */
class SyncEntitlementIntegrationTest {

    private val server = FakeSupabaseServer()

    private fun tableRequests() = server.requests.filter { it.table != null && it.table != "entitlement" }

    private fun tokenRefreshes() = server.requests.count { it.path == "/auth/v1/token" }

    private fun lastSyncedAt(device: TestDevice): Long? = device.database.appSettingsQueries.selectSettings().executeAsOneOrNull()?.lastSyncedAtEpochMs

    private fun state(device: TestDevice) = device.database.syncStateQueries.selectState().executeAsOneOrNull()

    // --- No claim ----------------------------------------------------------

    @Test
    fun an_account_that_never_paid_is_not_entitled_and_nothing_is_sent_or_recorded() = runTest {
        val device = TestDevice(server, entitled = false)
        device.saveEntry("tmdb:movie:603")

        val outcome = device.sync()

        assertEquals(SyncOutcome.NotEntitled, outcome)
        assertTrue(tableRequests().isEmpty(), "neither a push the server refuses nor a pull it answers with nothing: ${tableRequests().map { it.path }}")
        assertEquals(1, tokenRefreshes(), "credentials that say no are renewed once, in case they predate a purchase")
        assertNull(lastSyncedAt(device), "a sync that could see nothing must not read as one that saw everything")
        assertNull(state(device)?.lastOutcome, "the paywall is not a failure")
        assertEquals(1L, device.database.syncStateQueries.countDirtyRows().executeAsOne(), "the edit is still waiting")
    }

    @Test
    fun a_token_minted_before_the_purchase_landed_is_renewed_and_the_cycle_goes_through() = runTest {
        val device = TestDevice(server, entitled = false)
        device.saveEntry("tmdb:movie:603")
        // The webhook has landed since this device last got a token — on this
        // device or another: desktop never sells, its user paid on a phone.
        server.grantEntitlement("alice")

        val outcome = device.sync()

        assertIs<SyncOutcome.Success>(outcome)
        assertEquals(1, outcome.pushedCount)
        assertEquals(1, tokenRefreshes())
        assertNotNull(server.row("collection_entry", "alice", "tmdb:movie:603"))
        assertNotNull(lastSyncedAt(device))
    }

    @Test
    fun a_lapsed_grant_is_not_entitled_and_the_server_rows_are_kept() = runTest {
        val device = TestDevice(server)
        device.saveEntry("tmdb:movie:603")
        assertIs<SyncOutcome.Success>(device.sync())
        server.revokeEntitlement("alice")
        // The token that carried the claim has gone; the next one does not.
        server.expireAccessToken(FakeSupabaseServer.accessToken("alice"))
        device.saveEntry("tmdb:movie:604")

        assertEquals(SyncOutcome.NotEntitled, device.sync())

        assertEquals(listOf("tmdb:movie:603"), server.rows("collection_entry", "alice").map { it.getValue("media_id").toString().trim('"') }, "a lapse read-locks the account; it purges nothing (ADR 0019)")
    }

    // --- 403 / 42501 -------------------------------------------------------

    @Test
    fun a_push_refused_because_the_claim_ran_out_mid_cycle_is_not_entitled() = runTest {
        // The claim is still live by this device's clock when the cycle starts,
        // and past by the time the server sees the push.
        val claimEndsAt = 10_000L
        val token = FakeSupabaseServer.accessToken("bob", syncUntil = "1970-01-01T00:00:10Z")
        val device = TestDevice(server, userId = "bob")
        server.signUp("bob", accessToken = token)
        device.sessionStore.save(sessionFor("bob", accessToken = token))
        device.saveEntry("tmdb:movie:603")
        server.onRequest = { request -> if (request.isUpsert) device.clock.advanceTo(claimEndsAt + 1) }

        val outcome = device.sync()

        assertEquals(SyncOutcome.NotEntitled, outcome)
        assertTrue(server.requestsTo("collection_entry", "POST").isNotEmpty(), "the push was attempted and refused")
        assertNull(state(device)?.lastOutcome)
    }

    @Test
    fun a_403_42501_while_the_claim_is_live_stays_a_failure() = runTest {
        // What a forged co-watch row gets, and any write the policy forbids for a
        // reason other than payment. Showing a paywall to a paying user for it
        // would be wrong twice over.
        val device = TestDevice(server)
        device.saveEntry("tmdb:movie:603")
        server.failWith(403, body = """{"code":"42501","message":"new row violates row-level security policy for table \"collection_entry\""}""") { it.isUpsert }

        val outcome = device.sync()

        assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(SyncFailureReason.Unauthorised, outcome.reason)
        assertEquals("FAILED", state(device)?.lastOutcome)
    }

    // --- An empty pull -----------------------------------------------------

    @Test
    fun a_pull_that_lost_its_claim_midway_does_not_record_a_successful_sync() = runTest {
        server.seed("collection_entry", "bob", entryRow("tmdb:movie:603"))
        val token = FakeSupabaseServer.accessToken("bob", syncUntil = "1970-01-01T00:00:10Z")
        val device = TestDevice(server, userId = "bob")
        server.signUp("bob", accessToken = token)
        device.sessionStore.save(sessionFor("bob", accessToken = token))
        // Past the claim as the first page is requested: the server answers []
        // and the device's clock agrees the grant is over.
        server.onRequest = { request -> if (request.isSelect) device.clock.advanceTo(10_001L) }

        val outcome = device.sync()

        assertEquals(SyncOutcome.NotEntitled, outcome)
        assertNull(lastSyncedAt(device), "200 [] looks exactly like \"nothing new\"; it must not advance \"last synced\"")
        assertNull(device.entry("tmdb:movie:603"))
    }

    // --- Delete account ----------------------------------------------------

    @Test
    fun deleting_the_account_removes_the_server_data_signs_out_and_keeps_the_library() = runTest {
        val device = TestDevice(server)
        device.saveEntry("tmdb:movie:603")
        assertIs<SyncOutcome.Success>(device.sync())

        val outcome = device.engine.deleteAccount()

        assertEquals(AccountDeletionOutcome.Deleted, outcome)
        assertTrue(server.isDeleted("alice"))
        assertTrue(server.rows("collection_entry", "alice").isEmpty())
        assertNull(device.backend.session.first(), "signed out locally")
        assertTrue(device.sessionStore.cleared, "and the session does not come back on a restart")
        assertNotNull(device.entry("tmdb:movie:603"), "the device's own library is kept")
        assertNull(state(device)?.ownerAccountId, "it no longer belongs to an account that exists")
        assertTrue(device.database.syncCursorQueries.selectAll().executeAsList().isEmpty(), "the cursors were a dead account's")
        val request = server.requests.single { it.path == "/functions/v1/delete-account" }
        assertEquals("Bearer ${FakeSupabaseServer.accessToken("alice")}", request.headers.getValue("Authorization"))
    }

    @Test
    fun after_deleting_the_account_a_new_one_adopts_the_library_instead_of_asking_to_discard_it() = runTest {
        val device = TestDevice(server)
        device.saveEntry("tmdb:movie:603")
        assertIs<SyncOutcome.Success>(device.sync())
        device.engine.deleteAccount()

        device.signInAs("alice-again")
        val outcome = device.sync()

        assertIs<SyncOutcome.Success>(outcome, "an AccountChanged here would offer Replace — the default — and wipe the library")
        assertNotNull(server.row("collection_entry", "alice-again", "tmdb:movie:603"))
    }

    @Test
    fun a_failed_deletion_keeps_the_session_and_the_owner() = runTest {
        val device = TestDevice(server)
        assertIs<SyncOutcome.Success>(device.sync())
        server.failWith(500, body = """{"error":"delete failed"}""") { it.path == "/functions/v1/delete-account" }

        val outcome = device.engine.deleteAccount()

        assertEquals(AccountDeletionOutcome.Failed(SyncFailureReason.Server), outcome)
        assertNotNull(device.backend.session.first())
        assertEquals("alice", state(device)?.ownerAccountId)
        assertTrue(!server.isDeleted("alice"))
    }

    @Test
    fun deleting_while_signed_out_reaches_nothing() = runTest {
        val device = TestDevice(server)
        device.backend.signOut()
        server.clearRecordedRequests()

        assertEquals(AccountDeletionOutcome.NotSignedIn, device.engine.deleteAccount())
        assertTrue(server.requests.isEmpty())
    }

    // --- The backend's entitlement surface ---------------------------------

    @Test
    fun the_backend_reads_its_own_entitlement_row() = runTest {
        val device = TestDevice(server, entitled = false)
        assertNull(device.backend.fetchEntitlement().getOrThrow(), "never paid: no row")

        server.grantEntitlement("alice", expiresAtEpochMs = 1_762_647_375_289L)

        assertEquals(ServerEntitlement(active = true, expiresAtEpochMs = 1_762_647_375_289L), device.backend.fetchEntitlement().getOrThrow())
    }

    @Test
    fun refreshing_the_session_picks_up_a_grant_the_old_token_predates() = runTest {
        val device = TestDevice(server, entitled = false)
        assertNull(device.backend.syncGrantedUntil())
        server.grantEntitlement("alice")

        device.backend.refreshSession().getOrThrow()

        assertEquals(253_402_300_799_000L, device.backend.syncGrantedUntil(), "9999-12-31T23:59:59Z, a grant with no end")
    }

    private fun entryRow(mediaId: String) = kotlinx.serialization.json.buildJsonObject {
        put("media_id", kotlinx.serialization.json.JsonPrimitive(mediaId))
        put("media_type", kotlinx.serialization.json.JsonPrimitive("MOVIE"))
        put("title", kotlinx.serialization.json.JsonPrimitive("Title"))
        put("production_status", kotlinx.serialization.json.JsonPrimitive("RELEASED"))
        put("added_at_epoch_ms", kotlinx.serialization.json.JsonPrimitive(1L))
        put("updated_at_epoch_ms", kotlinx.serialization.json.JsonPrimitive(1L))
    }
}
