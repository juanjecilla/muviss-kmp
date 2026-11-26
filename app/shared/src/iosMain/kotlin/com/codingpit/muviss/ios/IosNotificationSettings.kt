package com.codingpit.muviss.ios

import com.codingpit.muviss.core.common.notifications.NotificationStatus
import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * iOS's [SystemNotificationSettings] (EPIC 47, #778). A refusal is as sticky here as on
 * Android 13+: once the user says no, iOS never shows the prompt again, and only the
 * app's page in the Settings app can undo it.
 *
 * iOS only answers asynchronously (`getNotificationSettingsWithCompletionHandler`), so
 * [statusNow] waits for that answer and derives both fields from the one status it returns.
 * There is no cache: a cached status can predate a change made in iOS Settings (#846, #864).
 */
internal object IosNotificationSettings : SystemNotificationSettings {

    override suspend fun statusNow(): NotificationStatus {
        val status = authorizationNow()
        return NotificationStatus(
            blocked = notificationBlocked(status),
            canAskAgain = notificationCanAskAgain(status),
        )
    }

    override fun open() {
        val url = NSURL.URLWithString(UIApplicationOpenNotificationSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private suspend fun authorizationNow(): Long = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            continuation.resume(settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined)
        }
    }
}
