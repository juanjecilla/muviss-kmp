package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The badge sits on a poster, so the worst case for its amber text is the
 * lightest artwork there can be: a white backdrop under the scrim. This is the
 * formula-level bound; the rendered pixels are measured in jvmTest's
 * `RatingBadgeRenderedContrastTest`, over the same worst cases and more.
 *
 * WCAG AA requires 4.5:1 for normal text (the badge label is 11 sp) and 3:1 for
 * non-text graphics such as the star icon (SC 1.4.11).
 */
class RatingBadgeContrastTest {

    @Test
    fun amber_text_on_the_scrim_over_a_white_poster_meets_aa_for_text() {
        val ratio = ratioOverWhitePoster()
        assertTrue(ratio >= 4.5, "amber on white-poster scrim is $ratio:1, WCAG AA needs 4.5:1")
    }

    @Test
    fun the_star_icon_over_a_white_poster_meets_aa_for_graphics() {
        val ratio = ratioOverWhitePoster()
        assertTrue(ratio >= 3.0, "amber star on white-poster scrim is $ratio:1, WCAG AA needs 3:1")
    }

    @Test
    fun the_scrim_is_not_more_opaque_than_the_text_needs() {
        // Guard against creeping back to an opaque pill: the artwork should
        // stay visible through the badge.
        assertTrue(
            RatingBadgeScrim.alpha < 0.9f,
            "scrim alpha ${RatingBadgeScrim.alpha} hides more of the poster than needed",
        )
    }

    private fun ratioOverWhitePoster(): Double {
        val backdrop = RatingBadgeScrim.compositeOver(Color.White)
        return wcagContrastRatio(RatingBadgeAmber, backdrop)
    }
}
