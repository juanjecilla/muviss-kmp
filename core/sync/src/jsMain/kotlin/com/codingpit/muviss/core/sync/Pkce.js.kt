package com.codingpit.muviss.core.sync

// The browser has both primitives, but only asynchronously
// (`crypto.subtle.digest` returns a Promise) while these are plain functions,
// and web sync has nowhere to keep a session anyway — the SQL.js database is
// session-only, so a signed-in user would be signed out by a page reload (see
// CLAUDE.md's web persistence note). Unsupported until both change.

internal actual fun sha256(input: ByteArray): ByteArray = unsupportedOnThisTarget()

internal actual fun secureRandomBytes(size: Int): ByteArray = unsupportedOnThisTarget()

private fun unsupportedOnThisTarget(): Nothing = error("OAuth sign-in is not wired up on the web yet — see Pkce.js.kt")
