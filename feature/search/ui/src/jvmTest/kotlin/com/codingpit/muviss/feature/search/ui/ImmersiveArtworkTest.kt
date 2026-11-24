package com.codingpit.muviss.feature.search.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure rule behind the immersive status bar: artwork covers the bar when its edges put it over `[0, inset)`. The
 * hero in these tests is 200 px of artwork plus the inset, placed at the top of the window and scrolled up by `offset`,
 * so its edges are `top = -offset` and `bottom = 200 + inset - offset`.
 */
class ImmersiveArtworkTest {

    private val artworkPx = 200f

    private fun heroCovers(offset: Float, inset: Float): Boolean {
        val top = -offset
        val bottom = artworkPx + inset - offset
        return artworkCoversBar(top, bottom, inset)
    }

    @Test
    fun no_bar_is_never_covered() {
        for (offset in listOf(-40f, 0f, 1f, 200f, 300f)) {
            assertFalse(heroCovers(offset, inset = 0f), "offset $offset with no bar")
        }
    }

    @Test
    fun rest_is_covered_for_every_inset() {
        for (inset in listOf(24f, 48f)) {
            assertTrue(heroCovers(offset = 0f, inset = inset), "rest at inset $inset")
        }
    }

    @Test
    fun the_artwork_still_touches_the_bar_one_pixel_before_its_bottom_clears_it() {
        for (inset in listOf(24f, 48f)) {
            val lastTouching = artworkPx + inset - 1f
            assertTrue(heroCovers(offset = lastTouching, inset = inset), "offset $lastTouching at inset $inset")
        }
    }

    @Test
    fun the_bottom_edge_exactly_on_the_top_of_the_screen_no_longer_touches_the_bar() {
        for (inset in listOf(24f, 48f)) {
            val clearing = artworkPx + inset
            assertFalse(heroCovers(offset = clearing, inset = inset), "offset $clearing at inset $inset")
        }
    }

    @Test
    fun one_pixel_past_the_clearing_offset_the_icons_are_off() {
        for (inset in listOf(24f, 48f)) {
            val past = artworkPx + inset + 1f
            assertFalse(heroCovers(offset = past, inset = inset), "offset $past at inset $inset")
        }
    }

    @Test
    fun the_boundary_moves_with_the_inset_not_a_fixed_offset() {
        // 24 dp of inset and 48 dp of inset must differ: the same offset is covered at 48 and not at 24.
        val offset = artworkPx + 24f
        assertFalse(heroCovers(offset, inset = 24f))
        assertTrue(heroCovers(offset, inset = 48f))
    }

    @Test
    fun artwork_whose_top_is_at_or_below_the_bar_does_not_cover_it() {
        assertFalse(artworkCoversBar(artworkTopPx = 24f, artworkBottomPx = 300f, topInsetPx = 24f))
        assertFalse(artworkCoversBar(artworkTopPx = 80f, artworkBottomPx = 300f, topInsetPx = 24f))
    }

    @Test
    fun artwork_pulled_down_by_overscroll_still_covers_the_top_of_the_bar() {
        assertTrue(artworkCoversBar(artworkTopPx = 12f, artworkBottomPx = 300f, topInsetPx = 24f))
    }

    @Test
    fun tracker_is_covered_only_while_some_artwork_touches_the_bar() {
        val artwork = ImmersiveArtwork()
        assertFalse(artwork.coversBar(24f), "nothing reported")

        val backdrop = Any()
        artwork.report(backdrop, top = -100f, bottom = 124f)
        assertTrue(artwork.coversBar(24f))

        val poster = Any()
        artwork.report(poster, top = 100f, bottom = 208f)
        artwork.report(backdrop, top = -300f, bottom = -100f)
        assertFalse(artwork.coversBar(24f), "the poster starts below the bar and the backdrop has left it")

        artwork.report(poster, top = -10f, bottom = 98f)
        assertTrue(artwork.coversBar(24f), "the poster overhang alone keeps the bar covered")

        artwork.forget(poster)
        assertFalse(artwork.coversBar(24f), "a forgotten node no longer counts")
    }

    @Test
    fun tracker_with_artwork_entirely_above_the_screen_is_off_at_every_inset() {
        val artwork = ImmersiveArtwork()
        artwork.report(Any(), top = -230f, bottom = -6f)
        for (inset in listOf(0f, 24f, 48f)) {
            assertFalse(artwork.coversBar(inset), "artwork above the window at inset $inset")
        }
    }
}
