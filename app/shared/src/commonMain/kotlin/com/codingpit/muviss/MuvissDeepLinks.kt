package com.codingpit.muviss

/**
 * The `muviss://` links that open a screen other than the one the app would start on.
 *
 * Shared by the platform hosts so the URL is spelled once: the Android widget builds its intent from
 * [NEXT_PICK_URL], and the Android manifest's `next-pick` intent filter must match [HOST_NEXT_PICK] by
 * hand (XML cannot reference this constant). iOS's `IosDeepLinks` parses the same host, and the SwiftUI
 * widget reads [NEXT_PICK_URL] through `IosWidgetBridge`.
 *
 * `muviss://title/<id>` is not here: each platform already routes it through its own channel (a
 * notification extra on Android, `IosDeepLinks.pendingDeepLinkMediaId` on iOS). Only the Pick Session
 * needs a link of its own.
 */
object MuvissDeepLinks {

    const val SCHEME = "muviss"

    /** Opens the Pick Session (EPIC 51), from the home-screen widget's empty state. */
    const val HOST_NEXT_PICK = "next-pick"

    const val NEXT_PICK_URL = "$SCHEME://$HOST_NEXT_PICK"

    /** Whether a parsed URL's scheme and host name the Pick Session. A null part never matches. */
    fun isNextPick(scheme: String?, host: String?): Boolean = scheme == SCHEME && host == HOST_NEXT_PICK
}
