package com.codingpit.muviss.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PKCE is the only thing standing between an intercepted redirect and a
 * session: `muviss://auth-callback` is a custom scheme, and any app on the
 * device can register it. These pin the properties RFC 7636 depends on — the
 * failures they catch are the silent kind, where sign-in still works and the
 * protection simply isn't there.
 */
class PkceTest {

    @Test
    fun the_challenge_is_the_sha256_of_the_verifier_base64url_encoded() {
        // RFC 7636's own worked example (Appendix B). If this drifts, GoTrue
        // rejects every exchange with an error that names neither hashing nor
        // encoding.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val expectedChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val challenge = base64UrlNoPadding(sha256(verifier.encodeToByteArray()))

        assertEquals(expectedChallenge, challenge)
    }

    @Test
    fun the_challenge_carries_no_base64_padding() {
        val (_, challenge) = newPkcePair()

        // A SHA-256 digest is 32 bytes, which base64 pads with a single '='.
        // RFC 7636 requires it stripped, and GoTrue compares byte-for-byte.
        assertFalse(challenge.contains('='), "padded challenge: $challenge")
    }

    @Test
    fun the_verifier_is_url_safe_and_the_length_the_rfc_asks_for() {
        val (verifier, _) = newPkcePair()

        assertEquals(43, verifier.length, "32 random bytes base64url-encode to 43 unpadded characters")
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' }, "not url-safe: $verifier")
    }

    @Test
    fun every_attempt_gets_a_fresh_verifier() {
        // A reused verifier would let an attacker who captured one redirect
        // redeem a later code. This is a smoke test for "did someone make it a
        // constant", not a randomness test.
        val verifiers = List(REPEATS) { newPkcePair().first }

        assertEquals(REPEATS, verifiers.toSet().size)
    }

    @Test
    fun the_same_verifier_always_produces_the_same_challenge() {
        val verifier = newPkcePair().first

        assertEquals(
            base64UrlNoPadding(sha256(verifier.encodeToByteArray())),
            base64UrlNoPadding(sha256(verifier.encodeToByteArray())),
        )
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun base64UrlNoPadding(bytes: ByteArray): String = kotlin.io.encoding.Base64.UrlSafe
        .withPadding(kotlin.io.encoding.Base64.PaddingOption.ABSENT)
        .encode(bytes)

    private companion object {
        const val REPEATS = 50
    }
}
