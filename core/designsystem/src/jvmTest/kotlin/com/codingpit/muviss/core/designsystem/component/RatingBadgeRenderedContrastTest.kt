@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.testing.GoldenSurface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The formula test bounds the badge with arithmetic; this one reads the pixels
 * the badge actually draws, so a change in how the scrim composites, in the
 * label's colour, or in what the Surface paints would show up here even if the
 * constants stayed put.
 *
 * The real artwork is not in the repo, so the backdrops are [SyntheticPoster]s:
 * white (the lightest), mid-grey, black (the darkest), and a two-tone checker
 * (the adversarial case, where every glyph straddles both tones). Real posters
 * remain unmeasured; see docs/ACCESSIBILITY.md.
 *
 * How a measurement is read. The pill is captured through [RATING_BADGE_TAG],
 * so the scrim and the backdrop it composites over are both in the pixels. The
 * sample is the pill's central band, which lies wholly inside its rounded ends,
 * so no backdrop outside the pill is read by mistake. The text colour is the
 * brightest pixel in the band: the glyph ink, which is the palest thing drawn
 * there. The background is the lightest tone that covers at least
 * [DOMINANT_SHARE] of the band, so a glyph's anti-aliasing (a few pixels of each
 * of many intermediate colours) never counts as background. That is the worst
 * tone the text can actually sit on.
 */
class RatingBadgeRenderedContrastTest {

    @Test
    fun the_label_over_the_lightest_poster_meets_aa_for_text() = runComposeUiTest {
        val measured = measureBadge(SyntheticPoster.White, darkTheme = false)
        assertAaForText(measured, SyntheticPoster.White)
    }

    @Test
    fun the_label_over_a_mid_grey_poster_meets_aa_for_text() = runComposeUiTest {
        val measured = measureBadge(SyntheticPoster.MidGrey, darkTheme = false)
        assertAaForText(measured, SyntheticPoster.MidGrey)
    }

    @Test
    fun the_label_over_the_darkest_poster_meets_aa_for_text() = runComposeUiTest {
        val measured = measureBadge(SyntheticPoster.Black, darkTheme = false)
        assertAaForText(measured, SyntheticPoster.Black)
    }

    @Test
    fun the_label_over_a_high_frequency_checker_meets_aa_for_text() = runComposeUiTest {
        val measured = measureBadge(SyntheticPoster.Checker, darkTheme = false)
        assertAaForText(measured, SyntheticPoster.Checker)
    }

    @Test
    fun the_rendered_badge_over_the_checker_is_the_same_in_dark_theme() = runComposeUiTest {
        // The badge sits on a poster, so the theme must not change its pixels:
        // this is checked against the same amber and scrim-over-white tone as the light run.
        val measured = measureBadge(SyntheticPoster.Checker, darkTheme = true)
        assertAaForText(measured, SyntheticPoster.Checker)
    }

    private fun assertAaForText(measured: Measured, poster: SyntheticPoster) {
        // Sanity first: a measurement of the wrong pixels would pass the ratio check by accident.
        val expectedBackground = RatingBadgeScrim.compositeOver(poster.lightest)
        assertTrue(
            channelsWithin(measured.ink, RatingBadgeAmber, tolerance = 2),
            "measured ink ${measured.ink.hex()} is not the amber ${RatingBadgeAmber.hex()}",
        )
        assertTrue(
            channelsWithin(measured.background, expectedBackground, tolerance = 2),
            "measured background ${measured.background.hex()} is not the scrim over " +
                "${poster.lightest.hex()} (${expectedBackground.hex()})",
        )
        println(
            "RATING-BADGE-CONTRAST poster=${poster.name} ink=${measured.ink.hex()} " +
                "background=${measured.background.hex()} ratio=%.2f:1".format(measured.ratio),
        )
        assertTrue(
            measured.ratio >= 4.5,
            "rendered label over ${poster.name} is ${measured.ratio}:1, WCAG AA needs 4.5:1",
        )
    }

    private fun ComposeUiTest.measureBadge(poster: SyntheticPoster, darkTheme: Boolean): Measured {
        setContent { BadgeOnPoster(poster, darkTheme) }
        waitForIdle()
        val pixels = onNodeWithTag(RATING_BADGE_TAG).captureToImage().toPixelMap()
        val height = pixels.height
        // Stadium-shaped pill: the rounded ends have radius height/2, so this band is inside it.
        val band = (height / 2 until pixels.width - height / 2).flatMap { x ->
            (0 until height).map { y -> pixels[x, y] }
        }
        val counts = band.groupingBy { it.toArgb() }.eachCount()
        val ink = counts.keys.map { Color(it) }.maxBy { wcagRelativeLuminance(it) }
        val dominant = counts.filterValues { it >= band.size * DOMINANT_SHARE }.keys.map { Color(it) }
        val background = dominant.filter { it != ink }.maxBy { wcagRelativeLuminance(it) }
        return Measured(ink = ink, background = background, ratio = wcagContrastRatio(ink, background))
    }

    private data class Measured(val ink: Color, val background: Color, val ratio: Double)

    private fun channelsWithin(a: Color, b: Color, tolerance: Int): Boolean {
        fun ch(c: Color) = listOf(c.red, c.green, c.blue).map { (it * 255f + 0.5f).toInt() }
        return ch(a).zip(ch(b)).all { (x, y) -> abs(x - y) <= tolerance }
    }

    private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

    private companion object {
        /** A tone must cover this share of the band to count as the background the text sits on. */
        const val DOMINANT_SHARE = 0.05
    }
}
