package com.codingpit.muviss

import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.ios.IosDeepLinks

/**
 * iOS entry point. Startup itself (Koin, `CrashReporter`, `BGTaskScheduler`
 * registration, notification permission) happens earlier, in
 * `IosAppStartup.start()` called from Swift's `AppDelegate` — see its doc
 * comment for why that can't wait for this composable to run.
 *
 * [IosDeepLinks] is this platform's equivalent of Android's
 * `MainActivity.deepLinkMediaId`/`oauthCode`: a notification tap, a widget
 * tap or an OAuth redirect sets one of its two pending values, they are fed
 * into `MuvissApp`, and the `on…Consumed` callbacks mirror Android's so
 * backgrounding/foregrounding neither re-navigates nor re-redeems.
 */
// Named in PascalCase to read as a UIViewController factory from Swift.
@Suppress("FunctionNaming")
fun MainViewController() = ComposeUIViewController {
    val deepLinkMediaId by IosDeepLinks.pendingDeepLinkMediaId.collectAsStateWithLifecycle()
    val oauthCode by IosDeepLinks.pendingOAuthCode.collectAsStateWithLifecycle()
    MuvissApp(
        deepLinkMediaId = deepLinkMediaId,
        onDeepLinkConsumed = { IosDeepLinks.clearPendingDeepLink() },
        oauthCode = oauthCode,
        onOAuthCodeConsumed = { IosDeepLinks.clearPendingOAuthCode() },
    )
}
