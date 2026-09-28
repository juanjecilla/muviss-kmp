@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The row shows five stars but still writes the 1-10 value the database and
 * every importer speak, so which half of which star was tapped is the whole
 * contract here. The theme is pinned because `MuvissTheme` otherwise defers to
 * the host machine's dark-mode setting.
 */
class RatingRowTest {

    private fun starTag(index: Int) = "$RATING_STAR_TAG_PREFIX$index"

    @Test
    fun tapping_the_right_half_of_the_third_star_stores_six() = runComposeUiTest {
        var rated: Int? = null
        setContent {
            MuvissTheme(darkTheme = true) {
                RatingRow(rating = null, onRate = { rated = it }, onClear = {})
            }
        }

        onNodeWithTag(starTag(2), useUnmergedTree = true).performTouchInput { click(Offset(width * 0.75f, height / 2f)) }

        assertEquals(6, rated)
    }

    @Test
    fun tapping_the_left_half_of_the_third_star_stores_five() = runComposeUiTest {
        var rated: Int? = null
        setContent {
            MuvissTheme(darkTheme = true) {
                RatingRow(rating = null, onRate = { rated = it }, onClear = {})
            }
        }

        onNodeWithTag(starTag(2), useUnmergedTree = true).performTouchInput { click(Offset(width * 0.25f, height / 2f)) }

        assertEquals(5, rated)
    }

    @Test
    fun tapping_the_half_already_selected_clears_the_rating() = runComposeUiTest {
        var cleared = false
        var rated: Int? = null
        setContent {
            MuvissTheme(darkTheme = true) {
                RatingRow(rating = 7, onRate = { rated = it }, onClear = { cleared = true })
            }
        }

        // Stored 7 is the left half of the fourth star.
        onNodeWithTag(starTag(3), useUnmergedTree = true).performTouchInput { click(Offset(width * 0.25f, height / 2f)) }

        assertTrue(cleared)
        assertNull(rated)
    }

    /**
     * Regression for issue #133's audit: `RatingStar`'s tap detector is
     * `pointerInput(Unit)`, so its coroutine launches once and never
     * restarts for the life of the star (rightly — this slot isn't recycled
     * across different items the way the triage card stack was, so keying it
     * on `rating` would restart the detector mid-gesture for no reason). But
     * without `rememberUpdatedState` around the tap handler, that one-shot
     * coroutine stays bound to whichever `rating` was current the *first*
     * time the star composed — so tapping the already-selected half again,
     * with the row staying mounted the whole time (the real-world case: a
     * detail screen's rating row, not a fresh composition per tap the way
     * the other tests in this file construct it), silently re-applied the
     * *original* rating forever instead of clearing it.
     */
    @Test
    fun tapping_the_same_half_again_after_a_live_update_still_clears_it() = runComposeUiTest {
        var current: Int? = null
        setContent {
            var rating by remember { mutableStateOf<Int?>(null) }
            current = rating
            MuvissTheme(darkTheme = true) {
                RatingRow(rating = rating, onRate = { rating = it }, onClear = { rating = null })
            }
        }

        // First tap: left half of the third star -> stores 5.
        onNodeWithTag(starTag(2), useUnmergedTree = true).performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        waitForIdle()
        assertEquals(5, current)

        // Second tap on the same half, row still mounted: must clear, not
        // silently re-store 5 because the closure saw a stale `rating`.
        onNodeWithTag(starTag(2), useUnmergedTree = true).performTouchInput { click(Offset(width * 0.25f, height / 2f)) }
        waitForIdle()
        assertNull(current)
    }

    @Test
    fun there_are_five_stars_not_ten() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                RatingRow(rating = 10, onRate = {}, onClear = {})
            }
        }

        onNodeWithTag(starTag(4), useUnmergedTree = true).assertExists()
        onNodeWithTag(starTag(5), useUnmergedTree = true).assertDoesNotExist()
    }
}
