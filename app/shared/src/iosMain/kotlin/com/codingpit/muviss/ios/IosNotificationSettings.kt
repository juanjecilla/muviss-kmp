package com.codingpit.muviss.ios

import com.codingpit.muviss.core.common.notifications.SystemNotificationSettings
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.concurrent.AtomicInt

/**
 * iOS's [SystemNotificationSettings] (EPIC 47). A refusal is as sticky here as on
 * Android 13+: once the user says no, iOS never shows the prompt again, and only the
 * app's page in the Settings app can undo it.
 *
 * [blocked] is synchronous but iOS only answers asynchronously
 * (`getNotificationSettingsWithCompletionHandler`), so it returns the last answer and
 * asks again. The answer is refreshed at startup and after every permission request,
 * so it is current by the time a screen reads it. Coming back from the Settings app
 * can leave it one read behind; the next resume corrects it.
 */
internal object IosNotificationSettings : SystemNotificationSettings {

    private val denied = AtomicInt(0)

    override fun blocked(): Boolean {
        refresh()
        return denied.value == 1
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
