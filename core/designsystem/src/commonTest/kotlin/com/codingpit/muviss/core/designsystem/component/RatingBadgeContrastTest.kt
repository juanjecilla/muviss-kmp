package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The badge sits on a poster, so the worst case for its amber text is the
 * lightest artwork there can be: a white backdrop under the scrim. The
 * luminance below is the WCAG 2.x formula written out in full rather than read
 * from `Color.luminance()`, so this test checks the number the accessibility
 * note reports, not Compose's implementation of it.
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
        return contrastRatio(RatingBadgeAmber, backdrop)
    }

    private fun relativeLuminance(c: Color): Double {
        fun linear(channel: Float): Double {
            val v = channel.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }
}
