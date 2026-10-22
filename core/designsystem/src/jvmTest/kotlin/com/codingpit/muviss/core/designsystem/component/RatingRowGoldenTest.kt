@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlin.test.Test

/**
 * The half star is a filled star clipped at its own midpoint, laid over the
 * outline. Getting that wrong doesn't fail a behavioural test — the taps still
 * store the right number — it just looks broken, which is exactly what a
 * golden is for. The first attempt sized a wrapper box to half a star around
 * an oversized child, and the amber rendered offset from the outline it was
 * meant to be filling.
 *
 * The surface is small so the stars are large in the image, and the theme is
 * pinned because `MuvissTheme` otherwise follows the host machine's setting.
 * Both themes are recorded: the unlit outline is the colour most likely to
 * vanish against one of the two backgrounds.
 */
class RatingRowGoldenTest {

    @Test
    fun a_half_star_fills_the_left_half_of_its_own_outline() = runComposeUiTest {
        setContent { RatingSample(rating = 5, darkTheme = true) }
        assertMatchesGolden("rating-row-half-star")
    }

    @Test
    fun a_whole_rating_lights_every_star() = runComposeUiTest {
        setContent { RatingSample(rating = 10, darkTheme = true) }
        assertMatchesGolden("rating-row-full")
    }

    @Test
    fun a_half_star_fills_the_left_half_of_its_own_outline_light() = runComposeUiTest {
        setContent { RatingSample(rating = 5, darkTheme = false) }
        assertMatchesGolden("rating-row-half-star-light")
    }

    @Test
    fun a_whole_rating_lights_every_star_light() = runComposeUiTest {
        setContent { RatingSample(rating = 10, darkTheme = false) }
        assertMatchesGolden("rating-row-full-light")
    }
}

@Composable
private fun RatingSample(rating: Int, darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface(width = 260.dp, height = 60.dp) {
            Box(Modifier.padding(4.dp)) {
                RatingRow(rating = rating, onRate = {}, onClear = {})
            }
        }
    }
}
