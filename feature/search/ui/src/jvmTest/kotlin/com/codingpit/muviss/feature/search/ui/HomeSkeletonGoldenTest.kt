@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
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
 * Tolerance is the default. The skeleton is flat tones and rounded shapes, so there are no glyphs to drift.
 */
class HomeSkeletonGoldenTest {

    @Test
    fun the_loading_home_shows_skeleton_rows_under_real_headers() = runComposeUiTest {
        loadingHome(darkTheme = false, animate = true)
        assertMatchesGolden("home-skeleton")
    }

    @Test
    fun the_loading_home_shows_skeleton_rows_under_real_headers_dark() = runComposeUiTest {
        loadingHome(darkTheme = true, animate = true)
        assertMatchesGolden("home-skeleton-dark")
    }

    @Test
    fun the_reduced_motion_skeleton_is_a_flat_fill() = runComposeUiTest {
        loadingHome(darkTheme = false, animate = false)
        assertMatchesGolden("home-skeleton-still")
    }

    private fun ComposeUiTest.loadingHome(darkTheme: Boolean, animate: Boolean) {
        mainClock.autoAdvance = false
        setContent { LoadingHomeUnderTest(darkTheme, animate) }
        mainClock.advanceTimeBy(FIXED_SWEEP_MILLIS, ignoreFrameDuration = true)
        waitForIdle()
    }
}

/** About 0.7 of a sweep in: the highlight band is on the middle of the screen, not at an edge. */
private const val FIXED_SWEEP_MILLIS = 700L

@Composable
private fun LoadingHomeUnderTest(darkTheme: Boolean, animate: Boolean) {
    // The local cards are real; the network sections are still out.
    val state = SearchUiState(
        loading = true,
        hasForYouSeeds = true,
        hasMuted = true,
        animateSkeleton = animate,
    )
    GoldenSurface {
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
