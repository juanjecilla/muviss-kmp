@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissSpacing
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.EpisodeDetails
import com.codingpit.muviss.models.EpisodeId
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Episode Detail's still runs sideways into a landscape cutout, as the Image Viewer and Detail's hero do (#1067,
 * #1089). The scrolling body is what clips it, so the body extends into the bands and pads its text back inside the
 * safe area. Skiko reports zero insets, so the tests pass a synthetic cutout.
 */
class EpisodeStillSideInsetsTest {

    @Test
    fun the_still_runs_under_both_cutout_bands_and_the_text_stays_inside_the_safe_area() = runComposeUiTest {
        val cutout = WindowInsets(left = 40.dp, top = 0.dp, right = 24.dp, bottom = 0.dp)
        setContent { Body(cutout) }

        val still = onNodeWithTag(EPISODE_STILL_TAG).getBoundsInRoot()
        assertEquals((-40).dp, still.left, "the still starts under the left cutout band, not at the scroll's edge")
        assertEquals(424.dp, still.right, "the still ends under the right cutout band")
        // The safe area is 0..400dp; the bands are the 40dp and 24dp outside it. Text starts a page gutter inside it.
        val title = onNodeWithTag(EPISODE_TITLE_TAG).getBoundsInRoot()
        assertEquals(MuvissSpacing.l, title.left, "text starts a page gutter inside the left safe edge")
    }

    @Test
    fun the_same_cutout_right_to_left_puts_the_text_on_the_logical_start_inset() = runComposeUiTest {
        val cutout = WindowInsets(left = 40.dp, top = 0.dp, right = 24.dp, bottom = 0.dp)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) { Body(cutout) }
        }

        val still = onNodeWithTag(EPISODE_STILL_TAG).getBoundsInRoot()
        assertEquals((-40).dp, still.left, "the still still runs under the physical left cutout")
        assertEquals(424.dp, still.right)
        // In RTL the logical start is the right band, so the title (start-aligned) ends a gutter inside the right edge.
        val title = onNodeWithTag(EPISODE_TITLE_TAG).getBoundsInRoot()
        assertEquals(400.dp - MuvissSpacing.l, title.right, "text starts a page gutter inside the right safe edge")
    }

    @Composable
    private fun Body(cutout: WindowInsets) {
        MuvissTheme(darkTheme = true) {
            Box(Modifier.width(400.dp).fillMaxSize()) {
                EpisodeBody(
                    details = details(),
                    state = EpisodeDetailUiState(loading = false, details = details()),
                    onWatchedAgain = {},
                    onUndoLatest = {},
                    onClearHistory = {},
                    onOpenImage = { _: ImageKind, _: String -> },
                    onOpenPerson = null,
                    horizontalInsets = cutout,
                )
            }
        }
    }

    private fun details() = EpisodeDetails(
        id = EpisodeId(MediaId.tmdbTv("1399"), 3, 9),
        name = "The Long Night",
        seasonNumber = 3,
        episodeNumber = 9,
        stillUrl = null,
    )
}
