package com.codingpit.muviss.ios

import com.codingpit.muviss.core.common.notifications.NotificationPermissionRequester

/** iOS's [NotificationPermissionRequester] (EPIC 47): the system prompt, shown when the user turns alerts on. */
internal object IosNotificationPermissionRequester : NotificationPermissionRequester {
    override suspend fun request(): Boolean = IosNotificationCenter.requestPermission()
}
