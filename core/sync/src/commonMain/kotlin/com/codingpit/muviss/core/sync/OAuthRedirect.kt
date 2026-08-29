package com.codingpit.muviss.core.sync

/**
 * Where the OAuth provider sends the user back to.
 *
 * This exact string has to appear in three places that cannot reference each
 * other, so treat it as the source of truth and change all three together:
 *
 *  1. here, as the `redirect_to` Muviss sends to GoTrue;
 *  2. `app/androidApp/src/main/AndroidManifest.xml`, as the intent filter's
 *     `<data android:scheme>` / `android:host` — XML cannot read a Kotlin
 *     constant;
 *  3. `supabase/config.toml`'s `additional_redirect_urls`, applied with
 *     `supabase config push`. GoTrue refuses to redirect anywhere not on that
 *     allow-list, which is what stops another app from talking Supabase into
 *     delivering a code somewhere else.
 *
 * A custom scheme rather than an App Link because an App Link needs a domain
 * Muviss does not own. That is precisely why the flow is PKCE: a custom
 * scheme can be registered by any app on the device, so the redirect alone
 * must not be enough to obtain a session (see [newPkcePair]).
 */
const val OAUTH_REDIRECT_URI: String = "muviss://auth-callback"

/** The query parameter GoTrue puts the authorization code in when it returns to [OAUTH_REDIRECT_URI]. */
const val OAUTH_CODE_PARAM: String = "code"
