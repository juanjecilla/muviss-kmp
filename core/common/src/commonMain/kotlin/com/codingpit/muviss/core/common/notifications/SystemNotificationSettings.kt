package com.codingpit.muviss.core.common.notifications

/**
 * What the OS says about Muviss's notifications, read in one go (#1016): both answers come from
 * the same moment, so a screen can never pair a "blocked" from before a change with a "can ask
 * again" from after it.
 */
data class NotificationStatus(
    /** True when the OS will not show Muviss's notifications, whatever the in-app switch says. */
    val blocked: Boolean,
    /**
     * Whether asking the OS again could still show a prompt (#326). False once the person has refused
     * and the OS has stopped asking (Android's "don't ask again", iOS's `denied`): a request then answers
     * "no" at once, so a screen should explain the switch rather than offer it. A platform that cannot tell says true.
     */
    val canAskAgain: Boolean,
)

/**
 * What the OS says about Muviss's notifications, for the screens that need to
 * explain a switch that cannot work (EPIC 30, #73).
 *
 * On Android 13+ a denied `POST_NOTIFICATIONS` is sticky: the system stops
 * asking, and Settings' "Notifications" switch then stores a preference the
 * notifier silently ignores. [statusNow] lets that row say so, and [open] takes
 * the user to the one place it can be undone. Platforms without such a
 * permission bind [None], under which nothing is ever blocked.
 *
 * There is deliberately no synchronous read. iOS answers only asynchronously, and a cached
 * answer can predate a change made in iOS Settings (#846, #864), so every caller asks the OS now.
 */
interface SystemNotificationSettings {
    /** The OS's answer as it is now. */
    suspend fun statusNow(): NotificationStatus

    /** Opens the system screen where the user can allow them again. */
    fun open()

    object None : SystemNotificationSettings {
        override suspend fun statusNow(): NotificationStatus = NotificationStatus(blocked = false, canAskAgain = true)
        override fun open() = Unit
    }
}
