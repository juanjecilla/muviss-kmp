package com.codingpit.muviss

import com.codingpit.muviss.feature.onboarding.ui.OnboardingRoute
import com.codingpit.muviss.feature.search.ui.SearchRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** EPIC 47: where a session starts. */
class StartRouteTest {

    @Test
    fun nothing_is_drawn_until_the_flag_has_been_read() {
        assertNull(startRouteFor(completed = null, hasDeepLink = false))
        assertNull(startRouteFor(completed = null, hasDeepLink = true))
    }

    @Test
    fun a_fresh_install_starts_on_the_introduction() {
        assertEquals(OnboardingRoute(), startRouteFor(completed = false, hasDeepLink = false))
    }

    @Test
    fun an_install_that_has_seen_it_or_predates_it_starts_on_search() {
        assertEquals(SearchRoute, startRouteFor(completed = true, hasDeepLink = false))
    }

    @Test
    fun a_widget_or_notification_launch_lands_where_it_was_tapped_even_on_a_fresh_install() {
        // The introduction stays pending for the next plain launch: nothing marks it completed here.
        assertEquals(SearchRoute, startRouteFor(completed = false, hasDeepLink = true))
    }
}
