package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A skeleton row draws a placeholder for every card the loaded row starts inside the width (#747 review). Cards are
 * 110 dp with a 12 dp gap, so card k starts at k * 122 dp.
 */
class CarouselSkeletonCountTest {

    @Test
    fun a_partly_visible_last_card_is_drawn() {
        // The third card starts at 244 dp, inside 328 dp, and shows 84 dp of itself.
        assertEquals(3, carouselSkeletonCount(328.dp))
    }

    @Test
    fun a_card_that_starts_exactly_at_the_edge_is_not_drawn() {
        // The third card would start at 244 dp, exactly the width: nothing of it is inside.
        assertEquals(2, carouselSkeletonCount(244.dp))
    }

    @Test
    fun a_card_just_past_the_edge_is_not_drawn() {
        assertEquals(2, carouselSkeletonCount(243.dp))
    }

    @Test
    fun a_width_under_one_card_still_shows_one_placeholder() {
        assertEquals(1, carouselSkeletonCount(110.dp))
        assertEquals(1, carouselSkeletonCount(0.dp))
    }
}
