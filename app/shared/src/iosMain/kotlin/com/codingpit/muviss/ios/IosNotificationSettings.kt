package com.codingpit.muviss.ios

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
 * iOS only answers asynchronously (`getNotificationSettingsWithCompletionHandler`), so the
 * last authorization status is cached, and [blocked] and [canAskAgain] read that cache through
 * the pure mappings in `IosNotificationAuthorization.kt`. Only [blockedNow] and [canAskAgainNow]
 * wait for iOS's answer; decisions that must see a change the person just made in Settings
 * (the Notification Nudge, #846) use those. The cache is refreshed at startup and after every
 * permission request or fresh read. Before the first answer it reads "not determined": nothing
 * is blocked and a prompt can still be shown.
 */
internal object IosNotificationSettings : SystemNotificationSettings {

    private val authorization = LatestAuthorization(UNAuthorizationStatusNotDetermined)

    /**
     * The cached answer only. It deliberately does not start a refresh: a fire-and-forget read
     * issued before a change can complete after a fresher [refreshNow] and overwrite it (#846).
     * Decisions that must see the change use [blockedNow] / [canAskAgainNow].
     */
    override fun blocked(): Boolean = notificationBlocked(authorization.status)

    /** The cached answer only; see [blocked] (#846). */
    override fun canAskAgain(): Boolean = notificationCanAskAgain(authorization.status)

    override suspend fun blockedNow(): Boolean = notificationBlocked(refreshNow())

    override suspend fun canAskAgainNow(): Boolean = notificationCanAskAgain(refreshNow())

    override fun open() {
        val url = NSURL.URLWithString(UIApplicationOpenNotificationSettingsURLString) ?: return
        UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    fun refresh() {
        val read = authorization.nextRead()
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            authorization.publish(read, settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined)
        }
    }

    /**
     * Waits for iOS's answer, caches it if it is the newest read, and returns this read's status. The return value
     * can be older than the cache when a later read answered first; deciders guard for that themselves (#846).
     */
    suspend fun refreshNow(): Long = suspendCancellableCoroutine { continuation ->
        val read = authorization.nextRead()
        UNUserNotificationCenter.currentNotificationCenter().getNotificationSettingsWithCompletionHandler { settings ->
            val status = settings?.authorizationStatus ?: UNAuthorizationStatusNotDetermined
            authorization.publish(read, status)
            continuation.resume(status)
        }
    }
}
