package com.codingpit.muviss.ios

import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #778: the nudge offers "Turn on" only while iOS would still show a prompt.
class IosNotificationAuthorizationTest {

    @Test
    fun not_determined_can_ask_and_is_not_blocked() {
        val status = UNAuthorizationStatusNotDetermined

        assertTrue(notificationCanAskAgain(status))
        assertFalse(notificationBlocked(status))
    }

    @Test
    fun denied_is_blocked_and_cannot_ask_again() {
        val status = UNAuthorizationStatusDenied

        assertTrue(notificationBlocked(status))
        assertFalse(notificationCanAskAgain(status))
    }

    @Test
    fun authorized_is_not_blocked() {
        val status = UNAuthorizationStatusAuthorized

        assertFalse(notificationBlocked(status))
        assertTrue(notificationCanAskAgain(status))
    }

    @Test
    fun provisional_and_ephemeral_are_not_blocked() {
        assertFalse(notificationBlocked(UNAuthorizationStatusProvisional))
        assertFalse(notificationBlocked(UNAuthorizationStatusEphemeral))
        assertTrue(notificationCanAskAgain(UNAuthorizationStatusProvisional))
        assertTrue(notificationCanAskAgain(UNAuthorizationStatusEphemeral))
    }

    @Test
    fun unknown_status_is_not_blocked_and_can_ask() {
        val unknown = 99L

        assertFalse(notificationBlocked(unknown))
        assertTrue(notificationCanAskAgain(unknown))
    }
}
