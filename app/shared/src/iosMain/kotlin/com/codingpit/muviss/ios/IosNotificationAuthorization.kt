package com.codingpit.muviss.ios

import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusNotDetermined

/**
 * Whether iOS will not show Muviss's notifications for this authorization status (#778).
 * Only a refusal blocks: a granted, provisional or ephemeral status shows them.
 */
internal fun notificationBlocked(status: Long): Boolean = status == UNAuthorizationStatusDenied

/**
 * Whether asking iOS again could still show a prompt (#778). iOS shows the prompt only while
 * the status is `notDetermined`; after a refusal (`denied`) a request answers "no" at once.
 * Any other status is one the system does not prompt for either, and is not an answer that
 * blocks, so it reads as "can ask" like every platform that cannot tell.
 */
internal fun notificationCanAskAgain(status: Long): Boolean = when (status) {
    UNAuthorizationStatusNotDetermined -> true
    UNAuthorizationStatusDenied -> false
    else -> true
}
