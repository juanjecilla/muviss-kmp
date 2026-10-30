@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlin.test.Test

/**
 * The badge is drawn over artwork this repo does not own, so its legibility is
 * decided by the scrim and the amber, not by the poster. A golden catches a
 * change to either: a lighter scrim shows as the pill turning grey over white,
 * and a recoloured star or label shows as a palette change even when the moved
 * area is small.
 *
 * Only the two ends of the range are recorded here: the lightest poster (white)
 * is where the scrim has to hold, and black is where the scrim is invisible and
 * the amber shows at its true value. The grey and checker backdrops are measured
 * by `RatingBadgeRenderedContrastTest`, which needs the pixels, not a picture.
 *
 * `darkTheme` is passed explicitly, as in the other goldens. The badge itself
 * ignores the theme (it sits on a poster), so the two recordings of each poster
 * should match; recording both still guards against a future change that does
 * read the theme.
 */
class RatingBadgeGoldenTest {

    @Test
    fun badge_over_the_lightest_poster_is_legible() = runComposeUiTest {
        setContent { BadgeOnPoster(SyntheticPoster.White, darkTheme = true) }
        assertMatchesGolden("rating-badge-white-dark", tolerance = BADGE_TOLERANCE)
    }

    @Test
    fun badge_over_the_lightest_poster_is_legible_light() = runComposeUiTest {
        setContent { BadgeOnPoster(SyntheticPoster.White, darkTheme = false) }
        assertMatchesGolden("rating-badge-white-light", tolerance = BADGE_TOLERANCE)
    }

    @Test
    fun badge_over_the_darkest_poster_is_legible() = runComposeUiTest {
        setContent { BadgeOnPoster(SyntheticPoster.Black, darkTheme = true) }
        assertMatchesGolden("rating-badge-black-dark", tolerance = BADGE_TOLERANCE)
    }

    @Test
    fun badge_over_the_darkest_poster_is_legible_light() = runComposeUiTest {
        setContent { BadgeOnPoster(SyntheticPoster.Black, darkTheme = false) }
        assertMatchesGolden("rating-badge-black-light", tolerance = BADGE_TOLERANCE)
    }
}

/**
 * Shared with the rendered measurement, which uses the same frame so its
 * numbers describe the badge as the goldens draw it.
 */
@Composable
internal fun BadgeOnPoster(poster: SyntheticPoster, darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface(width = BADGE_FRAME_WIDTH, height = BADGE_FRAME_HEIGHT) {
            Box(
                Modifier.fillMaxSize().syntheticPoster(poster),
                contentAlignment = Alignment.Center,
            ) {
                RatingBadge(rating = SAMPLE_RATING)
            }
        }
    }
}

/** Rating 9 renders as "4.5" on the five-star scale. */
internal const val SAMPLE_RATING = 9
internal val BADGE_FRAME_WIDTH = 80.dp
internal val BADGE_FRAME_HEIGHT = 40.dp

/**
 * The frame is 80x40dp and the pill with its label fills a large share of it,
 * so glyph edges are a big part of the image. Measured 2026-10-29 by rendering
 * the four macOS-recorded goldens on Linux (FreeType, Ubuntu 24.04 container,
 * `-PgoldenReport`): 1.16% moved on the black-poster pair and 0.94% on the
 * white one, every moved pixel a glyph edge, with no palette colour lost or
 * gained. 1.5% leaves that drift a margin and is well under what a colour
 * change moves; the palette check fails a colour change at any tolerance.
 */
private const val BADGE_TOLERANCE = 0.015
