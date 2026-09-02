package com.codingpit.muviss.core.sync

/**
 * Where the OAuth provider sends the user back to, on every platform that owns
 * a URL scheme.
 *
 * This exact string has to appear in three places that cannot reference each
 * other, so treat it as the source of truth and change all three together:
 *
 *  1. here, as the `redirect_to` Muviss sends to GoTrue;
 *  2. `app/androidApp/src/main/AndroidManifest.xml`, as the intent filter's
 *     `<data android:scheme>` / `android:host`, and `app/iosApp`'s Info.plist
 *     `CFBundleURLSchemes` — neither can read a Kotlin constant;
 *  3. `supabase/config.toml`'s `additional_redirect_urls`, applied with
 *     `supabase config push`. GoTrue refuses to redirect anywhere not on that
 *     allow-list, which is what stops another app from talking Supabase into
 *     delivering a code somewhere else.
 *
 * A custom scheme rather than an App Link because an App Link needs a domain
 * Muviss does not own. That is precisely why the flow is PKCE: a custom
 * scheme can be registered by any app on the device, so the redirect alone
 * must not be enough to obtain a session (see [newPkcePair]).
 *
 * Desktop is the exception and does not use this value at all — it has no
 * scheme to register that survives `./gradlew run`, so it supplies its own
 * loopback URL through [OAuthRedirectTarget]. See ADR 0017.
 */
const val OAUTH_REDIRECT_URI: String = "muviss://auth-callback"

/** The query parameter GoTrue puts the authorization code in when it returns to [OAUTH_REDIRECT_URI]. */
const val OAUTH_CODE_PARAM: String = "code"

/**
 * Supplies the `redirect_to` for one sign-in attempt.
 *
 * A seam rather than [OAUTH_REDIRECT_URI] read directly, because desktop's
 * redirect is not a constant: it is a `http://127.0.0.1:<port>/auth-callback`
 * whose port is only known once a socket has actually been bound, and binding
 * it is what makes the redirect receivable at all (ADR 0017). [reserve] is
 * therefore both "tell me the URL" and "start listening for it", and it
 * suspends because the second half can fail.
 *
 * Deliberately *not* an `expect val`: `:core:sync`'s entire test suite runs on
 * `jvmTest`, so a JVM actual would quietly rewrite what every existing sync
 * test asserts about `redirect_to`. Binding it in Koin instead leaves the
 * default in place everywhere except the one host that installs another.
 */
interface OAuthRedirectTarget {

    /**
     * Reserves a redirect target for a single attempt, returning the URL to
     * send GoTrue. Throws if the target cannot be reserved — on desktop, that
     * is every loopback port already being taken.
     */
    suspend fun reserve(): String

    /**
     * Releases the reservation. Called when an attempt fails before it starts,
     * and safe to call when nothing is reserved — a scheme-based target has
     * nothing to release, and a second call on desktop is a no-op.
     */
    fun release()
}

/**
 * The implementation for every platform that owns the `muviss://` scheme:
 * Android and iOS. Nothing is reserved because nothing has to be — the OS
 * routes the redirect to the app whether or not it was expecting one, which
 * is exactly how a redirect arriving on a cold start still works (ADR 0014).
 */
class DeepLinkRedirectTarget : OAuthRedirectTarget {
    override suspend fun reserve(): String = OAUTH_REDIRECT_URI
    override fun release() = Unit
}
