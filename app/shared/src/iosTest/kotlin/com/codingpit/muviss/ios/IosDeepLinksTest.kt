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
        IosDeepLinks.clearPendingOpenNextPick()
    }

    @Test
    fun next_pick_link_requests_the_pick_session_and_reports_recognised() {
        val recognised = IosDeepLinks.handle("muviss://next-pick")

        assertTrue(recognised)
        assertTrue(IosDeepLinks.pendingOpenNextPick.value)
        assertNull(IosDeepLinks.pendingDeepLinkMediaId.value)
    }

    @Test
    fun next_pick_link_with_a_trailing_path_still_requests_the_pick_session() {
        // Only the host is routed; a path after it is ignored rather than refused.
        assertTrue(IosDeepLinks.handle("muviss://next-pick/"))
        assertTrue(IosDeepLinks.pendingOpenNextPick.value)
    }

    @Test
    fun a_next_pick_request_is_cleared_once_consumed() {
        IosDeepLinks.handle("muviss://next-pick")

        IosDeepLinks.clearPendingOpenNextPick()

        assertFalse(IosDeepLinks.pendingOpenNextPick.value)
    }

    @Test
    fun next_pick_on_another_scheme_is_ignored() {
        val recognised = IosDeepLinks.handle("https://next-pick")

        assertFalse(recognised)
        assertFalse(IosDeepLinks.pendingOpenNextPick.value)
    }

    @Test
    fun a_pending_next_pick_leaves_a_pending_title_alone() {
        IosDeepLinks.handle("muviss://title/7")
        IosDeepLinks.handle("muviss://next-pick")

        IosDeepLinks.clearPendingOpenNextPick()

        assertEquals("7", IosDeepLinks.pendingDeepLinkMediaId.value)
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
