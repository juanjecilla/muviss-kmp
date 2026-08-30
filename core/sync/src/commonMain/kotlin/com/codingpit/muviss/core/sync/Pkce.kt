@file:OptIn(ExperimentalEncodingApi::class)

package com.codingpit.muviss.core.sync

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * SHA-256, per platform. Nothing in the build provides one: Kotlin's stdlib
 * has no hashing, and adding a multiplatform crypto dependency to a Kotlin
 * 2.4 / AGP 9 toolchain is the kind of version-fighting ADR 0009 already
 * declined once (see its `supabase-kt` section). Android and JVM have this
 * in the platform; the targets that cannot do the browser round-trip anyway
 * do not implement it.
 */
internal expect fun sha256(input: ByteArray): ByteArray

/**
 * Cryptographically secure random bytes, per platform.
 *
 * Deliberately not `kotlin.random.Random`, which is a general-purpose PRNG
 * and predictable given enough output. A guessable PKCE verifier defeats the
 * entire mechanism — an attacker who intercepts the redirect could then
 * redeem the code — so this has to come from the platform's CSPRNG.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray

/**
 * Builds a PKCE verifier/challenge pair (RFC 7636, S256).
 *
 * Base64url **without padding**: RFC 7636 §4.1 defines the verifier as
 * base64url-encoded with trailing `=` removed, and GoTrue compares the
 * challenge byte-for-byte, so padding here fails the exchange with an opaque
 * error rather than anything that names padding.
 */
internal fun newPkcePair(): Pair<String, String> {
    val verifier = base64Url(secureRandomBytes(VERIFIER_BYTES))
    val challenge = base64Url(sha256(verifier.encodeToByteArray()))
    return verifier to challenge
}

private fun base64Url(bytes: ByteArray): String = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(bytes)

/** 32 bytes → a 43-character verifier, the length RFC 7636 recommends (it allows 43–128). */
private const val VERIFIER_BYTES = 32
