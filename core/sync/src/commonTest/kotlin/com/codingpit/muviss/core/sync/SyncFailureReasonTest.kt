package com.codingpit.muviss.core.sync

import com.codingpit.muviss.core.sync.supabase.SupabaseHttpException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Named like the platform exceptions they stand in for: classification is by
// name because `java.net.UnknownHostException` does not exist on JS, Wasm or
// iOS, and a test in commonTest cannot construct the real one.
private class UnknownHostException(message: String) : RuntimeException(message)

private class SocketTimeoutException(message: String) : RuntimeException(message)

private class DarwinHttpRequestException(message: String) : RuntimeException(message)

class SyncFailureReasonTest {

    private fun http(status: Int) = SupabaseHttpException(status = status, what = "pull", body = "")

    @Test
    fun a_rejected_or_forbidden_request_is_unauthorised() {
        assertEquals(SyncFailureReason.Unauthorised, SyncFailureReason.classify(http(401)))
        assertEquals(SyncFailureReason.Unauthorised, SyncFailureReason.classify(http(403)))
    }

    @Test
    fun a_dead_session_is_unauthorised() {
        assertEquals(SyncFailureReason.Unauthorised, SyncFailureReason.classify(SyncSessionExpiredException()))
    }

    @Test
    fun any_other_http_status_is_a_server_failure() {
        listOf(400, 404, 409, 429, 500, 502, 503).forEach {
            assertEquals(SyncFailureReason.Server, SyncFailureReason.classify(http(it)), "HTTP $it")
        }
    }

    @Test
    fun timeouts_and_unreachable_hosts_are_offline() {
        assertEquals(SyncFailureReason.Offline, SyncFailureReason.classify(HttpRequestTimeoutException("https://x.supabase.co", 1_000)))
        assertEquals(SyncFailureReason.Offline, SyncFailureReason.classify(ConnectTimeoutException("connect timed out")))
        assertEquals(SyncFailureReason.Offline, SyncFailureReason.classify(UnknownHostException("x.supabase.co")))
        assertEquals(SyncFailureReason.Offline, SyncFailureReason.classify(SocketTimeoutException("read timed out")))
        assertEquals(SyncFailureReason.Offline, SyncFailureReason.classify(DarwinHttpRequestException("The Internet connection appears to be offline.")))
    }

    @Test
    fun a_browser_fetch_failure_is_offline() {
        assertEquals(SyncFailureReason.Offline, SyncFailureReason.classify(RuntimeException("TypeError: Failed to fetch")))
    }

    @Test
    fun the_cause_chain_is_searched() {
        val wrapped = IllegalStateException("push failed", UnknownHostException("x.supabase.co"))
        assertEquals(SyncFailureReason.Offline, SyncFailureReason.classify(wrapped))

        val wrappedHttp = IllegalStateException("push failed", http(401))
        assertEquals(SyncFailureReason.Unauthorised, SyncFailureReason.classify(wrappedHttp))
    }

    @Test
    fun anything_unrecognised_is_unknown() {
        assertEquals(SyncFailureReason.Unknown, SyncFailureReason.classify(IllegalStateException("boom")))
        assertEquals(SyncFailureReason.Unknown, SyncFailureReason.classify(RuntimeException()))
    }

    @Test
    fun the_stored_form_round_trips_the_reason_and_keeps_the_detail_out_of_it() {
        SyncFailureReason.entries.forEach { reason ->
            val stored = SyncFailureReason.encode(reason, "Supabase pull failed with HTTP 500: {\"message\":\"x\"}")
            assertEquals(reason, SyncFailureReason.fromStored(stored), stored)
        }
        assertEquals(SyncFailureReason.Offline, SyncFailureReason.fromStored(SyncFailureReason.encode(SyncFailureReason.Offline, null)))
    }

    @Test
    fun an_unreadable_stored_value_is_no_reason_at_all() {
        assertNull(SyncFailureReason.fromStored(null))
        assertNull(SyncFailureReason.fromStored(""))
        assertNull(SyncFailureReason.fromStored("Supabase pull failed with HTTP 500"))
    }
}
