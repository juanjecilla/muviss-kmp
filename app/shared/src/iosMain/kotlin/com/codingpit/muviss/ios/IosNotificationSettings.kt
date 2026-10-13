package com.codingpit.muviss.ios

import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume

/**
 * iOS's [SystemNotificationSettings] (EPIC 47). A refusal is as sticky here as on
 * Android 13+: once the user says no, iOS never shows the prompt again, and only the
 * app's page in the Settings app can undo it.
 *
 * [blocked] is synchronous but iOS only answers asynchronously
 * (`getNotificationSettingsWithCompletionHandler`), so it returns the last answer and
 * asks again; [blockedNow] waits for iOS's answer, and is what a screen re-checking on
 * resume uses. The cache is refreshed at startup and after every permission request.
 */
internal object IosNotificationSettings : SystemNotificationSettings {

    private val denied = AtomicInt(0)

    override fun blocked(): Boolean {
        refresh()
        return denied.value == 1
    }

    override suspend fun blockedNow(): Boolean = suspendCancellableCoroutine { continuation ->
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val isDenied = settings?.authorizationStatus == UNAuthorizationStatusDenied
            denied.value = if (isDenied) 1 else 0
            continuation.resume(isDenied)
        }
    }

    override fun open() {
        val url = NSURL.URLWithString(UIApplicationOpenNotificationSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    fun refresh() {
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            denied.value = if (settings?.authorizationStatus == UNAuthorizationStatusDenied) 1 else 0
        }
    }
}
