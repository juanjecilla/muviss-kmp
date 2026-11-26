@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.MediaDetails
import com.codingpit.muviss.models.MediaId
import com.codingpit.muviss.models.MediaSummary
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Detail's backdrop is the hero, and the Image Viewer runs edge to edge sideways under a landscape cutout. The hero
 * follows it: it draws into the side bands, so a landscape phone with a cutout shows no theme-coloured strip beside
 * the artwork. Skiko reports zero insets, so the test passes a synthetic cutout; a device reports the real one.
 */
class DetailHeroSideInsetsTest {

    @Test
    fun the_backdrop_draws_into_both_side_bands_of_a_landscape_cutout() = runComposeUiTest {
        val cutout = WindowInsets(left = 40.dp, top = 0.dp, right = 24.dp, bottom = 0.dp)
        setContent {
            MuvissTheme(darkTheme = true) {
                Box(Modifier.width(400.dp)) {
                    DetailBackdrop(details(), height = 200.dp, topInset = 0.dp, horizontalInsets = cutout)
                }
            }
        }

        val bounds = onNodeWithTag(DETAIL_BACKDROP_TAG).getUnclippedBoundsInRoot()
        assertEquals((-40).dp, bounds.left, "the backdrop starts under the left cutout band")
        assertEquals(424.dp, bounds.right, "the backdrop ends under the right cutout band")
    }

    /**
     * The production list: the hero draws into the cutout bands and is not clipped by the list it sits in, while an
     * ordinary row keeps the safe area's edges. Read through the clipped bounds, because that is what is drawn.
     */
    @Test
    fun the_hero_is_drawn_under_the_cutout_inside_the_real_detail_list() = runComposeUiTest {
        val cutout = WindowInsets(left = 40.dp, top = 0.dp, right = 24.dp, bottom = 0.dp)
        setContent {
            MuvissTheme(darkTheme = true) {
                Box(Modifier.width(400.dp)) {
                    DetailList(rememberLazyListState(), bottomPadding = 0.dp, horizontalInsets = cutout) {
                        item { DetailBackdrop(details(), height = 200.dp, topInset = 0.dp, horizontalInsets = cutout) }
                        item { Box(Modifier.fillMaxWidth().height(20.dp).testTag(ROW_TAG)) }
                    }
                }
            }
        }

        val hero = onNodeWithTag(DETAIL_BACKDROP_TAG).getBoundsInRoot()
        assertEquals((-40).dp, hero.left, "the hero is not cut at the list's edge")
        assertEquals(424.dp, hero.right)
        val row = onNodeWithTag(ROW_TAG).getBoundsInRoot()
        assertEquals(0.dp, row.left, "an ordinary row stays inside the safe area")
        assertEquals(400.dp, row.right)
    }

    @Test
    fun without_side_insets_the_backdrop_stays_inside_its_column() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                Box(Modifier.width(400.dp)) {
                    DetailBackdrop(details(), height = 200.dp, topInset = 0.dp, horizontalInsets = WindowInsets(0.dp))
                }
            }
        }

        val bounds = onNodeWithTag(DETAIL_BACKDROP_TAG).getUnclippedBoundsInRoot()
        assertEquals(0.dp, bounds.left)
        assertEquals(400.dp, bounds.right)
    }

    private companion object {
        const val ROW_TAG = "detailRow"
    }

    private fun details() = MediaDetails(
        summary = MediaSummary(id = MediaId.tmdbMovie("603"), title = "The Matrix", year = 1999, posterUrl = null),
        backdropUrl = null,
    )
}
