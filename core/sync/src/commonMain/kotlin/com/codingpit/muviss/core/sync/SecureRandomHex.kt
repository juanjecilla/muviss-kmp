package com.codingpit.muviss.core.sync

/**
 * A cryptographically-random lowercase hex string of [length] characters.
 *
 * Lives here because `secureRandomBytes` does — it is an `expect`/`actual` with
 * a real implementation per platform (PKCE, ADR 0014) — and co-watch needs the
 * same quality of randomness for an invite nonce without a second, weaker copy
 * appearing somewhere else. `kotlin.random.Random` would compile everywhere and
 * be the wrong thing.
 *
 * The nonce is not a secret that protects data: an invite code carries its
 * user id in the clear by necessity (ADR 0022). What it protects against is an
 * unsolicited row addressed at a known id presenting itself as an invitation,
 * so it has to be unguessable, not confidential.
 */
fun secureRandomHex(length: Int): String {
    require(length > 0) { "length must be positive" }
    val bytes = secureRandomBytes((length + 1) / 2)
    return buildString(length) {
        bytes.forEach { byte ->
            val value = byte.toInt() and 0xFF
            append(HEX[value shr 4])
            append(HEX[value and 0x0F])
        }
    }.take(length)
}

private const val HEX = "0123456789abcdef"
