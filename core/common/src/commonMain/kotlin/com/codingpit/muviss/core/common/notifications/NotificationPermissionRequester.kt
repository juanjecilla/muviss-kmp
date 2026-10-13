package com.codingpit.muviss.core.common.notifications

/**
 * Asks the OS for permission to show notifications, at the moment the user turns
 * them on (EPIC 47). Nothing asks cold any more: Android used to prompt once a
 * show was saved and iOS on every launch, and both are now this, called from
 * Onboarding, the Notification Nudge and Settings.
 *
 * [request] returns whether notifications may be shown *now*. On Android 13+ and
 * iOS the answer may need a system prompt; a refusal is sticky there, so asking
 * again simply returns false at once, and the one place it can be undone is
 * [SystemNotificationSettings.open]. Platforms with no such permission bind
 * [Granted], and a platform that never shows a notification (web) is not asked at
 * all.
 */
interface NotificationPermissionRequester {
    suspend fun request(): Boolean

    object Granted : NotificationPermissionRequester {
        override suspend fun request(): Boolean = true
    }
}
