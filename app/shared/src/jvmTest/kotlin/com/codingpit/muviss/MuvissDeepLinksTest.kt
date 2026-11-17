package com.codingpit.muviss

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MuvissDeepLinksTest {

    @Test
    fun the_next_pick_scheme_and_host_name_the_pick_session() {
        assertTrue(MuvissDeepLinks.isNextPick("muviss", "next-pick"))
    }

    @Test
    fun the_advertised_url_parses_back_to_the_next_pick_link() {
        val scheme = MuvissDeepLinks.NEXT_PICK_URL.substringBefore("://")
        val host = MuvissDeepLinks.NEXT_PICK_URL.substringAfter("://")

        assertTrue(MuvissDeepLinks.isNextPick(scheme, host))
    }

    @Test
    fun a_link_on_another_scheme_is_not_the_next_pick_link() {
        assertFalse(MuvissDeepLinks.isNextPick("https", "next-pick"))
    }

    @Test
    fun another_host_on_the_right_scheme_is_not_the_next_pick_link() {
        assertFalse(MuvissDeepLinks.isNextPick("muviss", "title"))
        assertFalse(MuvissDeepLinks.isNextPick("muviss", "auth-callback"))
    }

    @Test
    fun a_link_missing_its_scheme_or_host_is_not_the_next_pick_link() {
        assertFalse(MuvissDeepLinks.isNextPick(null, "next-pick"))
        assertFalse(MuvissDeepLinks.isNextPick("muviss", null))
        assertFalse(MuvissDeepLinks.isNextPick(null, null))
    }
}
