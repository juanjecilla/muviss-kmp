package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The light-icon request and the scrim's dark plateau share [ImmersiveScrimExtent]. The request holds while the
 * artwork has scrolled less than that extent, so the status-bar band sits at most at `offset + inset` below the top of
 * the artwork. The plateau must reach that far, or light icons land on bright artwork.
 */
class ImmersiveScrimTest {

    @Test
    fun the_bar_band_stays_inside_the_plateau_at_every_offset_the_request_holds_for() {
        for (inset in listOf(0.dp, 24.dp, 48.dp)) {
            // The request holds for offsets strictly below the extent, so its worst case is the extent itself.
            val worstBarBottom = ImmersiveScrimExtent + inset
            assertTrue(
                worstBarBottom <= immersiveScrimPlateau(inset),
                "bar bottom $worstBarBottom passes the plateau end ${immersiveScrimPlateau(inset)} at inset $inset",
            )
        }
    }

    @Test
    fun the_plateau_covers_the_whole_bar_at_rest() {
        for (inset in listOf(0.dp, 24.dp, 48.dp)) {
            assertTrue(inset <= immersiveScrimPlateau(inset))
        }
    }

    @Test
    fun the_plateau_is_the_inset_plus_the_extent_the_request_uses() {
        assertEquals(ImmersiveScrimExtent + 24.dp, immersiveScrimPlateau(24.dp))
    }
}
