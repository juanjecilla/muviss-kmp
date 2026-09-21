package com.codingpit.muviss.feature.cowatch.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Names are `underscore_case` because this is `commonTest`: Kotlin/Native
 * rejects punctuation in backticked function names, JVM accepts it, so a comma
 * here would pass `:feature:cowatch:domain:jvmTest` and only fail
 * `./gradlew build` nine minutes in (CLAUDE.md).
 */
class InviteCodeTest {

    private val userId = "3f2a1b4c-5d6e-4f70-8192-a3b4c5d6e7f8"
    private val nonce = "7d41e0a29c1b3f5e"

    @Test
    fun a_code_round_trips_through_its_pasteable_form() {
        val code = InviteCode(userId, nonce)
        assertEquals(code, InviteCode.decode(code.encode()).getOrThrow())
    }

    @Test
    fun a_code_round_trips_through_its_deep_link_form() {
        val code = InviteCode(userId, nonce)
        assertEquals(code, InviteCode.decode(code.toDeepLink()).getOrThrow())
    }

    @Test
    fun surrounding_whitespace_from_a_paste_is_tolerated() {
        val code = InviteCode(userId, nonce)
        assertEquals(code, InviteCode.decode("  ${code.encode()}\n").getOrThrow())
    }

    @Test
    fun a_code_is_normalised_to_lower_case_so_the_id_matches_what_the_server_returns() {
        val decoded = InviteCode.decode("${userId.uppercase()}.${nonce.uppercase()}").getOrThrow()
        assertEquals(userId, decoded.userId)
        assertEquals(nonce, decoded.nonce)
    }

    @Test
    fun something_that_is_not_a_user_id_is_refused() {
        // The id in a code becomes the address of a row this device writes, so
        // shape is checked rather than trusted.
        assertTrue(InviteCode.decode("not-a-uuid.$nonce").isFailure)
        assertTrue(InviteCode.decode("$nonce.$nonce").isFailure)
    }

    @Test
    fun a_malformed_nonce_is_refused() {
        assertTrue(InviteCode.decode("$userId.short").isFailure)
        assertTrue(InviteCode.decode("$userId.zzzzzzzzzzzzzzzz").isFailure)
        assertTrue(InviteCode.decode(userId).isFailure)
    }

    @Test
    fun an_empty_or_separator_only_string_is_refused() {
        assertTrue(InviteCode.decode("").isFailure)
        assertTrue(InviteCode.decode(".").isFailure)
        assertTrue(InviteCode.decode("$userId.").isFailure)
    }

    @Test
    fun a_deep_link_missing_either_half_is_refused() {
        assertTrue(InviteCode.decode("${InviteCode.DEEP_LINK_PREFIX}?u=$userId").isFailure)
        assertTrue(InviteCode.decode("${InviteCode.DEEP_LINK_PREFIX}?n=$nonce").isFailure)
    }
}
