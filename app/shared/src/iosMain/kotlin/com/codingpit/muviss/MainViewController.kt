package com.codingpit.muviss

import androidx.compose.runtime.getValue
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codingpit.muviss.ios.IosNotificationCenter

/**
 * iOS entry point. Startup itself (Koin, `CrashReporter`, `BGTaskScheduler`
 * registration, notification permission) happens earlier, in
 * `IosAppStartup.start()` called from Swift's `AppDelegate` — see its doc
 * comment for why that can't wait for this composable to run.
 *
 * [IosNotificationCenter.pendingDeepLinkMediaId] is this platform's
 * equivalent of Android's `MainActivity.deepLinkMediaId`: a notification
 * tap (foreground or cold-launch) sets it, it's fed into
 * `MuvissApp(deepLinkMediaId)` which navigates to that show's Detail once
 * composed, then [IosNotificationCenter.clearPendingDeepLink] mirrors
 * Android's `onDeepLinkConsumed` so backgrounding/foregrounding doesn't
 * re-navigate.
 */
// Named in PascalCase to read as a UIViewController factory from Swift.
@Suppress("FunctionNaming")
fun MainViewController() = ComposeUIViewController {
    val deepLinkMediaId by IosNotificationCenter.pendingDeepLinkMediaId.collectAsStateWithLifecycle()
    MuvissApp(
        deepLinkMediaId = deepLinkMediaId,
        onDeepLinkConsumed = { IosNotificationCenter.clearPendingDeepLink() },
    )
}
