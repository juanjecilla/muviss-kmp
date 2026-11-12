@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A carousel's skeleton card keeps the height of the loaded card it stands in for (#723): the caption reserves
 * the loaded two-line title, at every font scale, so the sections below a carousel do not move when it loads.
 */
class PosterCardSkeletonTest {

    @Test
    fun the_skeleton_card_is_as_tall_as_the_loaded_card_at_the_normal_font_scale() = runComposeUiTest {
        setContent { CardHeights(fontScale = 1f) }
        waitForIdle()
        assertSameHeight()
    }

    @Test
    fun the_skeleton_card_is_as_tall_as_the_loaded_card_at_a_large_font_scale() = runComposeUiTest {
        setContent { CardHeights(fontScale = 2f) }
        waitForIdle()
        assertSameHeight()
    }

    private fun ComposeUiTest.assertSameHeight() {
        val loaded = onNodeWithTag(LOADED_CARD_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot().height
        val skeleton = onNodeWithTag(SKELETON_CARD_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot().height
        assertEquals(loaded, skeleton, "the skeleton card is $skeleton tall, the loaded card $loaded")
    }
}

private const val LOADED_CARD_TAG = "loadedCard"
private const val SKELETON_CARD_TAG = "skeletonCard"

@Composable
private fun CardHeights(fontScale: Float) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
        MuvissTheme(darkTheme = false) {
            Row {
                PosterCard(
                    title = "Everything Everywhere All at Once",
                    posterUrl = null,
                    onClick = {},
                    modifier = Modifier.width(110.dp).testTag(LOADED_CARD_TAG),
                )
                PosterCardSkeleton(Modifier.width(110.dp).testTag(SKELETON_CARD_TAG))
            }
        }
    }
}
