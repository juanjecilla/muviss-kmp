package com.codingpit.muviss.core.sync

// Same reasoning as the js actual: `crypto.subtle` is async where these are
// not, and web sync has no session storage that survives a reload.

internal actual fun sha256(input: ByteArray): ByteArray = unsupportedOnThisTarget()

internal actual fun secureRandomBytes(size: Int): ByteArray = unsupportedOnThisTarget()

private fun unsupportedOnThisTarget(): Nothing = error("OAuth sign-in is not wired up on the web yet — see Pkce.wasmJs.kt")
