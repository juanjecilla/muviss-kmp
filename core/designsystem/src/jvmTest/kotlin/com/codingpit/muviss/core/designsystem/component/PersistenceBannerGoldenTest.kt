@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlin.test.Test

/**
 * The banner is the only thing standing between a web user in a second tab and
 * silently losing everything they do there, so what it looks like matters as
 * much as whether it renders: a warning that reads as decoration is not a
 * warning. Hence a golden rather than only a text assertion.
 *
 * `darkTheme` is passed explicitly because `MuvissTheme` otherwise follows the
 * host machine's setting, and a golden recorded in dark mode fails on CI with
 * nearly every pixel moved. Both themes are recorded, since a warning colour
 * that holds up on one background can wash out on the other.
 */
class PersistenceBannerGoldenTest {

    private val readOnlyTab = "Muviss is open in another tab. Changes here won't be saved."

    @Test
    fun the_read_only_tab_warning_is_legible() = runComposeUiTest {
        setContent { BannerSample(message = readOnlyTab, darkTheme = true) }

        assertMatchesGolden("persistence-banner-read-only-tab", tolerance = BANNER_TOLERANCE)
    }

    @Test
    fun the_read_only_tab_warning_is_legible_light() = runComposeUiTest {
        setContent { BannerSample(message = readOnlyTab, darkTheme = false) }

        assertMatchesGolden("persistence-banner-read-only-tab-light", tolerance = BANNER_TOLERANCE)
    }

    /**
     * `Durable` and `Pending` both arrive here as a null message, and both must
     * draw nothing — Pending especially, since it is what every writer tab
     * passes through on load and a flash of this banner would read as a bug.
     */
    @Test
    fun a_null_message_draws_nothing() = runComposeUiTest {
        setContent { BannerSample(message = null, darkTheme = true) }

        onAllNodes(hasText(readOnlyTab, substring = true)).assertCountEquals(0)
        assertMatchesGolden("persistence-banner-absent")
    }

    @Test
    fun a_null_message_draws_nothing_light() = runComposeUiTest {
        setContent { BannerSample(message = null, darkTheme = false) }

        onAllNodes(hasText(readOnlyTab, substring = true)).assertCountEquals(0)
        assertMatchesGolden("persistence-banner-absent-light")
    }
}

@Composable
private fun BannerSample(message: String?, darkTheme: Boolean) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface(width = 412.dp, height = 72.dp) {
            PersistenceBanner(message = message)
        }
    }
}

/**
 * The frame is 412x72dp and one line of text fills most of it, so the glyph
 * edges CoreText and FreeType draw differently are a large share of the image:
 * 3.17% on `ubuntu-latest` against a macOS recording with hinting pinned
 * (`GoldenSurface`, #171), every marked pixel a glyph edge. A colour change —
 * what this golden exists to catch — moves the whole banner, far above this.
 */
private const val BANNER_TOLERANCE = 0.035
