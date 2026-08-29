package com.codingpit.muviss.core.sync

// Deliberately unimplemented rather than half-implemented. iOS has both
// primitives (CommonCrypto's CC_SHA256, SecRandomCopyBytes), but nothing here
// can use them yet: OAuth needs a browser round-trip and a redirect back into
// the app, which on iOS means ASWebAuthenticationSession and a registered URL
// scheme, neither of which exists. `SupabaseSyncBackend.beginOAuth` reports
// sign-in as unsupported on this target before either of these is reached, so
// a stub that returned plausible bytes would only make a broken flow look
// workable. See ADR 0003: Android is the first-verify target.

internal actual fun sha256(input: ByteArray): ByteArray = unsupportedOnThisTarget()

internal actual fun secureRandomBytes(size: Int): ByteArray = unsupportedOnThisTarget()

private fun unsupportedOnThisTarget(): Nothing = error("OAuth sign-in is not wired up on iOS yet — see Pkce.ios.kt")
