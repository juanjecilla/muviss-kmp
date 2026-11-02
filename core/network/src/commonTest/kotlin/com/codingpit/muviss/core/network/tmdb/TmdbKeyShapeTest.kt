package com.codingpit.muviss.core.network.tmdb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TmdbKeyShapeTest {

    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJhYmMifQ.c2lnbmF0dXJl"
    private val hex = "0123456789abcdef0123456789ABCDEF"

    @Test
    fun a_v4_read_token_is_sent_as_a_bearer_credential() {
        val detected = TmdbKeyShape.detect(jwt)
        assertEquals(jwt, detected?.readToken)
        assertEquals("", detected?.apiKey)
    }

    @Test
    fun a_v3_key_is_sent_as_the_api_key_parameter() {
        val detected = TmdbKeyShape.detect(hex)
        assertEquals(hex, detected?.apiKey)
        assertEquals("", detected?.readToken)
    }

    @Test
    fun surrounding_whitespace_from_a_paste_is_ignored() {
        assertEquals(hex, TmdbKeyShape.detect("  $hex\n")?.apiKey)
    }

    @Test
    fun anything_else_is_neither() {
        assertNull(TmdbKeyShape.detect(""))
        assertNull(TmdbKeyShape.detect("hello"))
        assertNull(TmdbKeyShape.detect(hex.dropLast(1)), "31 hex characters")
        assertNull(TmdbKeyShape.detect(hex + "0"), "33 hex characters")
        assertNull(TmdbKeyShape.detect("eyJonly.two"), "a JWT has three segments")
        assertNull(TmdbKeyShape.detect("Bearer $jwt"), "the scheme word is not part of the token")
    }
}
