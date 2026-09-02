package com.codingpit.muviss.ios

import com.codingpit.muviss.core.sync.OAUTH_REDIRECT_URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents

/**
 * The single place a `muviss://` URL enters the app.
 *
 * Swift's `iOSApp.onOpenURL` hands the raw string here and nothing else —
 * parsing lives on this side so the `Shared` framework's Swift-facing surface
 * stays the flat strings ADR 0016's `IosWidgetBridge` note describes, rather
 * than growing route types.
 *
 * Two hosts, both of which already have a consumer in common code:
 *
 * - `muviss://title/<mediaId>` — the widget's `.widgetURL`
 *   (`MuvissWidget/WatchNextView.swift`). Feeds [pendingDeepLinkMediaId],
 *   which [com.codingpit.muviss.MainViewController] passes to
 *   `MuvissApp(deepLinkMediaId)`. This is the same channel a notification tap
 *   uses — see [IosNotificationCenter], which writes here rather than keeping
 *   a second copy of the state.
 * - `muviss://auth-callback?code=…` — the OAuth redirect (ADR 0014). Feeds
 *   [pendingOAuthCode] and so `MuvissApp(oauthCode)`, whose
 *   `CompleteOAuthOnRedirect` redeems it. Handled at the app root, not on the
 *   profile screen, because the redirect can arrive on a cold start long after
 *   `ProfileViewModel` is gone.
 *
 * Both are cleared by their consumer once acted on, so backgrounding and
 * foregrounding the app does not re-navigate or re-redeem.
 */
object IosDeepLinks {

    private val pendingDeepLinkMediaIdState = MutableStateFlow<String?>(null)
    private val pendingOAuthCodeState = MutableStateFlow<String?>(null)

    val pendingDeepLinkMediaId: StateFlow<String?> = pendingDeepLinkMediaIdState.asStateFlow()
    val pendingOAuthCode: StateFlow<String?> = pendingOAuthCodeState.asStateFlow()

    fun clearPendingDeepLink() {
        pendingDeepLinkMediaIdState.value = null
    }

    fun clearPendingOAuthCode() {
        pendingOAuthCodeState.value = null
    }

    /** Used by [IosNotificationCenter] for a notification tap, which carries an id rather than a URL. */
    fun openTitle(mediaId: String) {
        pendingDeepLinkMediaIdState.value = mediaId
    }

    /**
     * Routes one incoming URL. Returns whether it was recognised, which is what
     * Swift's `onOpenURL` needs to decide whether to hand it on.
     *
     * Unknown schemes and hosts are ignored rather than throwing: iOS delivers
     * whatever the system was asked to open, and a malformed link should not
     * take the app down.
     */
    fun handle(url: String): Boolean {
        val components = NSURLComponents(uRL = NSURL(string = url), resolvingAgainstBaseURL = false) ?: return false
        if (components.scheme != SCHEME) return false
        return when (components.host) {
            HOST_TITLE -> {
                // "muviss://title/<id>" — the id is the path, minus its leading slash.
                val mediaId = components.path?.removePrefix("/")?.takeIf { it.isNotBlank() } ?: return false
                openTitle(mediaId)
                true
            }

            HOST_AUTH_CALLBACK -> {
                val code = components.queryItems
                    ?.filterIsInstance<platform.Foundation.NSURLQueryItem>()
                    ?.firstOrNull { it.name == CODE_PARAM }
                    ?.value
                    ?.takeIf { it.isNotBlank() }
                    ?: return false
                pendingOAuthCodeState.value = code
                true
            }

            else -> false
        }
    }

    /**
     * Derived from [OAUTH_REDIRECT_URI] rather than repeated, so this cannot
     * drift from `:core:sync`, the Android manifest's intent filter and
     * `supabase/config.toml`'s `additional_redirect_urls` — the three places
     * ADR 0014 says must stay byte-identical and cannot reference each other.
     * `CFBundleURLTypes` in `app/iosApp/iosApp/Info.plist` is a fourth, and is
     * likewise plain text there.
     */
    private val SCHEME = OAUTH_REDIRECT_URI.substringBefore("://")
    private val HOST_AUTH_CALLBACK = OAUTH_REDIRECT_URI.substringAfter("://")

    private const val HOST_TITLE = "title"
    private const val CODE_PARAM = "code"
}
