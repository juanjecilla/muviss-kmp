package com.codingpit.muviss.core.sync

/**
 * An OAuth identity provider Muviss can sign in through.
 *
 * The client is deliberately provider-agnostic — GoTrue takes the name as a
 * query parameter (`/auth/v1/authorize?provider=github`) and everything
 * downstream is identical — so adding a provider is enabling it in the
 * Supabase dashboard plus one entry here, never a new code path.
 *
 * [wireName] is what GoTrue expects; [displayName] is what the sign-in button
 * says.
 */
enum class OAuthProvider(val wireName: String, val displayName: String) {
    GITHUB("github", "GitHub"),
    GOOGLE("google", "Google"),
}

/**
 * One in-flight OAuth attempt: the URL to send the user to, and the PKCE
 * verifier that will prove, when the redirect comes back, that the code was
 * issued to *this* attempt.
 *
 * The verifier never leaves the device and is never sent to the browser —
 * only its SHA-256 hash goes out, in the authorize URL. That is the whole
 * point of PKCE here: a custom scheme like `muviss://auth-callback` can be
 * registered by any other app on the device, so an intercepted redirect must
 * not be redeemable on its own.
 */
data class OAuthAttempt(
    val authorizeUrl: String,
    val codeVerifier: String,
)
