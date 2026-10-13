package com.codingpit.muviss.core.common.notifications

/**
 * What the OS says about Muviss's notifications, for the screens that need to
 * explain a switch that cannot work (EPIC 30, #73).
 *
 * On Android 13+ a denied `POST_NOTIFICATIONS` is sticky: the system stops
 * asking, and Settings' "Notifications" switch then stores a preference the
 * notifier silently ignores. [blocked] lets that row say so, and [open] takes
 * the user to the one place it can be undone. Platforms without such a
 * permission bind [None], under which nothing is ever blocked.
 */
interface SystemNotificationSettings {
    /** True when the OS will not show Muviss's notifications, whatever the in-app switch says. */
    fun blocked(): Boolean

    /**
     * [blocked], asked of the OS now. A platform that only answers asynchronously (iOS)
     * returns its last known answer from [blocked]; this waits for the fresh one, so a
     * screen re-checking on resume sees what the user just changed in system settings.
     */
    suspend fun blockedNow(): Boolean = blocked()

    /** Opens the system screen where the user can allow them again. */
    fun open()

    object None : SystemNotificationSettings {
        override fun blocked(): Boolean = false
        override fun open() = Unit
    }
}
