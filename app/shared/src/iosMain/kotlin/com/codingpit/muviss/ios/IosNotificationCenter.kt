package com.codingpit.muviss.ios

import com.codingpit.muviss.feature.collection.api.NewEpisodesResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.setValue
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionAlert
import platform.UserNotifications.UNNotificationPresentationOptionBadge
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * iOS counterpart of `:app:androidApp`'s `NewEpisodesNotifier` +
 * `MainActivity`'s permission request. Turns EPIC 5's `NewEpisodesResult`s
 * into local `UNUserNotificationCenter` notifications, and turns a
 * notification tap into a pending deep-link id that [MainViewController]
 * feeds into `MuvissApp(deepLinkMediaId)` — the same round-trip Android's
 * `MainActivity`/`EXTRA_DEEP_LINK_MEDIA_ID` does, just via a [StateFlow]
 * instead of an `Intent` extra since there is no Activity-recreation
 * equivalent to carry it through.
 *
 * No `NSUserNotificationsUsageDescription`-style Info.plist key is needed —
 * unlike camera/location, `UNUserNotificationCenter`'s permission prompt
 * uses fixed system copy.
 */
object IosNotificationCenter {

    private val pendingDeepLinkMediaIdState = MutableStateFlow<String?>(null)

    /** Consumed by [MainViewController]; cleared via [clearPendingDeepLink] once `MuvissApp` navigates. */
    val pendingDeepLinkMediaId: StateFlow<String?> = pendingDeepLinkMediaIdState.asStateFlow()

    fun clearPendingDeepLink() {
        pendingDeepLinkMediaIdState.value = null
    }

    /**
     * Installs the tap delegate and requests permission. Safe to call on
     * every launch, background or foreground: [UNUserNotificationCenter]
     * only prompts the user the first time — a later call with a status
     * already determined just invokes the completion handler immediately.
     */
    fun configure() {
        UNUserNotificationCenter.currentNotificationCenter().delegate = delegate
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(options) { _, _ -> }
    }

    /**
     * Mirrors `NewEpisodesNotifier.notify`'s per-show notification; iOS
     * groups same-`threadIdentifier` notifications into a stack in Notification
     * Center itself, so no separate hand-built summary notification is
     * needed the way Android's `InboxStyle` summary is.
     */
    fun postNewEpisodeNotifications(results: List<NewEpisodesResult>) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        results.forEach { result ->
            // UNMutableNotificationContent's writable properties are declared
            // read-only on its NSObject superclass and only widened to
            // read-write in the ObjC subclass header; Kotlin/Native's interop
            // binds the inherited `val` at the property-reference site, so
            // KVC (`setValue:forKey:`, always available on NSObject) is used
            // instead of direct property assignment here.
            val content = UNMutableNotificationContent().apply {
                setValue(result.title, forKey = "title")
                setValue(result.contentText(), forKey = "body")
                setValue(GROUP_KEY, forKey = "threadIdentifier")
                setValue(mapOf(DEEP_LINK_KEY to result.mediaId.toString()), forKey = "userInfo")
            }
            val request = UNNotificationRequest.requestWithIdentifier(
                result.mediaId.toString(),
                content,
                null,
            )
            center.addNotificationRequest(request) { /* best-effort, mirrors NewEpisodesNotifier's graceful no-op on failure */ }
        }
    }

    /** Content text for the notification; its title already carries the show's name (same split as Android's `NewEpisodesNotifier`). */
    private fun NewEpisodesResult.contentText(): String = latestEpisodeLabel?.let { "$it is out" } ?: "Now available"

    private val delegate =
        object : NSObject(), UNUserNotificationCenterDelegateProtocol {
            override fun userNotificationCenter(
                center: UNUserNotificationCenter,
                willPresentNotification: UNNotification,
                withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
            ) {
                withCompletionHandler(
                    UNNotificationPresentationOptionAlert or UNNotificationPresentationOptionSound or UNNotificationPresentationOptionBadge,
                )
            }

            override fun userNotificationCenter(
                center: UNUserNotificationCenter,
                didReceiveNotificationResponse: UNNotificationResponse,
                withCompletionHandler: () -> Unit,
            ) {
                val mediaId = didReceiveNotificationResponse.notification.request.content.userInfo[DEEP_LINK_KEY] as? String
                if (mediaId != null) pendingDeepLinkMediaIdState.value = mediaId
                withCompletionHandler()
            }
        }

    private const val GROUP_KEY = "com.codingpit.muviss.NEW_EPISODES"
    private const val DEEP_LINK_KEY = "mediaId"
}
