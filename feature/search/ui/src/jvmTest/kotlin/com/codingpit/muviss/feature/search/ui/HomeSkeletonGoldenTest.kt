@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import com.codingpit.muviss.core.designsystem.component.SkeletonHost
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import com.codingpit.muviss.models.Genre
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaType
import kotlin.test.Test

/**
 * Home while it loads (#723): the local cards render for real, and each network section is a skeleton
 * under its real header, at the loaded size, so the page does not move when the data lands.
 *
 * The sweep is captured at a fixed point on a paused clock, so the band sits in the same place on every
 * run. [the_reduced_motion_skeleton_is_a_flat_fill] is the switch from #394: no sweep, just the base tone.
 *
 * The frame still carries text (the Triage card, the chip, the section headers), so it takes the tolerance the other
 * Home goldens with text use, not the tighter default: a skeleton can't drift on its own, but its headers can.
 */
class HomeSkeletonGoldenTest {

    @Test
    fun the_loading_home_shows_skeleton_rows_under_real_headers() = runComposeUiTest {
        loadingHome(darkTheme = false, animate = true)
        assertMatchesGolden("home-skeleton", tolerance = HOME_TOLERANCE)
    }

    @Test
    fun the_loading_home_shows_skeleton_rows_under_real_headers_dark() = runComposeUiTest {
        loadingHome(darkTheme = true, animate = true)
        assertMatchesGolden("home-skeleton-dark", tolerance = HOME_TOLERANCE)
    }

    @Test
    fun the_reduced_motion_skeleton_is_a_flat_fill() = runComposeUiTest {
        loadingHome(darkTheme = false, animate = false)
        assertMatchesGolden("home-skeleton-still", tolerance = HOME_TOLERANCE)
    }

    // An RTL Home at a quarter of a sweep, while the band is still on the right of the screen: the band travels
    // right to left (#746), so its place here mirrors the LTR frame at the same time.
    @Test
    fun the_loading_home_under_rtl_sweeps_the_band_from_the_right() = runComposeUiTest {
        loadingHome(darkTheme = false, animate = true, rtl = true, sweepMillis = RTL_SWEEP_MILLIS)
        assertMatchesGolden("home-skeleton-rtl", tolerance = HOME_TOLERANCE)
    }

    private fun ComposeUiTest.loadingHome(
        darkTheme: Boolean,
        animate: Boolean,
        rtl: Boolean = false,
        sweepMillis: Long = FIXED_SWEEP_MILLIS,
    ) {
        mainClock.autoAdvance = false
        setContent { LoadingHomeUnderTest(darkTheme, animate, rtl) }
        mainClock.advanceTimeBy(sweepMillis, ignoreFrameDuration = true)
        waitForIdle()
    }
}

/** The tolerance of the Home goldens that carry text (see the class KDoc). */
private const val HOME_TOLERANCE = 0.04

/** Half a sweep in (the sweep is 1400 ms): the highlight band is on the middle of the screen, not at an edge. */
private const val FIXED_SWEEP_MILLIS = 700L

/** A quarter of a sweep in, so the RTL band is clearly on the right half of the screen. */
private const val RTL_SWEEP_MILLIS = 350L

@Composable
private fun LoadingHomeUnderTest(darkTheme: Boolean, animate: Boolean, rtl: Boolean) {
    // The local cards are real; the network sections are still out.
    val state = SearchUiState(
        loading = true,
        hasForYouSeeds = true,
        forYouLoading = true,
        hasMuted = true,
        animateSkeleton = animate,
    )
    val direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    GoldenSurface {
        CompositionLocalProvider(LocalLayoutDirection provides direction) {
            MuvissTheme(darkTheme = darkTheme) {
                SkeletonHost(animate = animate) {
                    DiscoverBrowse(
                        state,
                        onSelectGenre = { _: Genre, _: MediaType -> },
                        onOpenDetail = { _: MediaId -> },
                        onOpenTriage = {},
                        muted = MutedChip(available = true),
                    )
                }
            }
        }
    }
}
