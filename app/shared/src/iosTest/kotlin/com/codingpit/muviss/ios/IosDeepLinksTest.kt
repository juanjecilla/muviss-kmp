package com.codingpit.muviss.ios

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosDeepLinksTest {

    @BeforeTest
    fun clear_pending_state() {
        // IosDeepLinks is a process-wide object, so every test starts from nothing pending.
        IosDeepLinks.clearPendingDeepLink()
        IosDeepLinks.clearPendingOAuthCode()
    }

    @Test
    fun title_link_publishes_the_media_id_and_reports_recognised() {
        val recognised = IosDeepLinks.handle("muviss://title/12345")

        assertTrue(recognised)
        assertEquals("12345", IosDeepLinks.pendingDeepLinkMediaId.value)
    }

    @Test
    fun title_link_without_an_id_is_not_recognised_and_publishes_nothing() {
        val recognised = IosDeepLinks.handle("muviss://title/")

        assertFalse(recognised)
        assertNull(IosDeepLinks.pendingDeepLinkMediaId.value)
    }

    @Test
    fun auth_callback_publishes_the_code_and_reports_recognised() {
        val recognised = IosDeepLinks.handle("muviss://auth-callback?code=abc123")

        assertTrue(recognised)
        assertEquals("abc123", IosDeepLinks.pendingOAuthCode.value)
    }

    @Test
    fun auth_callback_without_a_code_is_not_recognised() {
        val recognised = IosDeepLinks.handle("muviss://auth-callback?error=access_denied")

        assertFalse(recognised)
        assertNull(IosDeepLinks.pendingOAuthCode.value)
    }

    @Test
    fun auth_callback_with_a_blank_code_is_not_recognised() {
        val recognised = IosDeepLinks.handle("muviss://auth-callback?code=")

        assertFalse(recognised)
        assertNull(IosDeepLinks.pendingOAuthCode.value)
    }

    @Test
    fun unknown_scheme_is_ignored_rather_than_thrown() {
        val recognised = IosDeepLinks.handle("https://example.com/title/12345")

        assertFalse(recognised)
        assertNull(IosDeepLinks.pendingDeepLinkMediaId.value)
    }

    @Test
    fun unknown_host_on_the_right_scheme_is_ignored() {
        val recognised = IosDeepLinks.handle("muviss://settings/notifications")

        assertFalse(recognised)
        assertNull(IosDeepLinks.pendingDeepLinkMediaId.value)
    }

    @Test
    fun malformed_url_is_ignored_rather_than_crashing_the_app() {
        val recognised = IosDeepLinks.handle("not a url at all")

        assertFalse(recognised)
        assertNull(IosDeepLinks.pendingDeepLinkMediaId.value)
        assertNull(IosDeepLinks.pendingOAuthCode.value)
    }

    @Test
    fun a_url_foundation_cannot_parse_is_ignored_rather_than_crashing_the_app() {
        // NSURL's initializer returns nil here, which Kotlin/Native turns into a NullPointerException.
        val recognised = IosDeepLinks.handle("muviss://[::1")

        assertFalse(recognised)
        assertNull(IosDeepLinks.pendingDeepLinkMediaId.value)
        assertNull(IosDeepLinks.pendingOAuthCode.value)
    }

    @Test
    fun clearing_the_pending_title_leaves_a_pending_code_alone() {
        IosDeepLinks.handle("muviss://title/1")
        IosDeepLinks.handle("muviss://auth-callback?code=xyz")

        IosDeepLinks.clearPendingDeepLink()

        assertNull(IosDeepLinks.pendingDeepLinkMediaId.value)
        assertEquals("xyz", IosDeepLinks.pendingOAuthCode.value)
    }
}
