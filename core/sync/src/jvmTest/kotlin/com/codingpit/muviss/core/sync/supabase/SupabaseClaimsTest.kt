package com.codingpit.muviss.core.sync.supabase

import com.codingpit.muviss.core.testing.FakeSupabaseServer
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `sync_until` reader fails closed: anything it cannot read is no grant. */
class SupabaseClaimsTest {

    private fun token(payload: String, padded: Boolean = false): String {
        val encoder = if (padded) Base64.getUrlEncoder() else Base64.getUrlEncoder().withoutPadding()
        return "${encoder.encodeToString("{\"alg\":\"HS256\"}".toByteArray())}.${encoder.encodeToString(payload.toByteArray())}.signature"
    }

    @Test
    fun the_hooks_claim_reads_as_epoch_millis() {
        assertEquals(1_794_183_375_000L, syncUntilClaimEpochMs(token("""{"sub":"u","sync_until":"2026-11-09T00:16:15Z"}""")))
    }

    @Test
    fun a_grant_with_no_end_reads_as_the_far_future() {
        assertEquals(253_402_300_799_000L, syncUntilClaimEpochMs(FakeSupabaseServer.accessToken("u")))
    }

    @Test
    fun a_padded_segment_is_tolerated() {
        // 1-byte payload length differences decide whether padding appears; try a few.
        listOf("a", "ab", "abc").forEach { filler ->
            assertEquals(1_794_183_375_000L, syncUntilClaimEpochMs(token("""{"x":"$filler","sync_until":"2026-11-09T00:16:15Z"}""", padded = true)))
        }
    }

    @Test
    fun no_claim_is_no_grant() {
        assertNull(syncUntilClaimEpochMs(token("""{"sub":"u","role":"authenticated"}""")))
    }

    @Test
    fun anything_malformed_is_no_grant() {
        assertNull(syncUntilClaimEpochMs("not-a-jwt"))
        assertNull(syncUntilClaimEpochMs("a.%%%.c"))
        assertNull(syncUntilClaimEpochMs(token("[]")))
        assertNull(syncUntilClaimEpochMs(token("""{"sync_until":12345}""")), "the hook writes a string; a number is not its claim")
        assertNull(syncUntilClaimEpochMs(token("""{"sync_until":"next tuesday"}""")))
    }

    @Test
    fun postgrests_timestamptz_rendering_parses() {
        assertEquals(1_794_183_375_289L, parseInstantEpochMs("2026-11-09T00:16:15.289+00:00"))
        assertEquals(1_794_183_375_289L, parseInstantEpochMs("2026-11-09T02:16:15.289+02:00"))
    }
}
