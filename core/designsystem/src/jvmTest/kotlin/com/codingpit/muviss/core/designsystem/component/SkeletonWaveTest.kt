@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The arithmetic of the sweep is in `:lib:shimmer`'s ShimmerWaveTest. This is the same wave through the
 * Muviss skeleton on a paused clock: the highlight has moved between two frames, and with the reduced-motion
 * switch off it has not moved at all.
 */
class SkeletonWaveTest {

    @Test
    fun the_wave_moves_across_the_placeholders_as_the_clock_advances() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { TwoPlaceholders(animate = true) }
        val early = frameAfter(EARLY_MILLIS)
        val later = frameAfter(LATER_MILLIS)

        assertNotEquals(early, later, "the sweep did not move between $EARLY_MILLIS ms and $LATER_MILLIS ms")
    }

    @Test
    fun a_reduced_motion_skeleton_does_not_move_as_the_clock_advances() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { TwoPlaceholders(animate = false) }
        val early = frameAfter(EARLY_MILLIS)
        val later = frameAfter(LATER_MILLIS)

        assertEquals(early, later, "a reduced-motion skeleton changed between frames")
    }

    /** The pixels of the skeleton row after the clock has moved [millis] from its start. */
    private fun ComposeUiTest.frameAfter(millis: Long): List<Long> {
        mainClock.advanceTimeBy(millis, ignoreFrameDuration = true)
        waitForIdle()
        val pixels = onNodeWithTag(SKELETON_ROW_TAG).captureToImage().toPixelMap()
        return buildList {
            for (y in 0 until pixels.height step SAMPLE_STEP) {
                for (x in 0 until pixels.width step SAMPLE_STEP) add(pixels[x, y].value.toLong())
            }
        }
    }
}

private const val SKELETON_ROW_TAG = "skeletonRow"
private const val SAMPLE_STEP = 4

/**
 * The first frame is 400 ms into the sweep, the second 800 ms (each call advances the clock by its own delta,
 * so the second is cumulative). Of a 1400 ms sweep that is about 0.3 and 0.6: the band sits elsewhere on the row.
 */
private const val EARLY_MILLIS = 400L
private const val LATER_MILLIS = 400L

@Composable
private fun TwoPlaceholders(animate: Boolean) {
    MuvissTheme(darkTheme = false) {
        SkeletonHost(animate = animate, modifier = Modifier.testTag(SKELETON_ROW_TAG)) {
            Row {
                PosterSkeleton(Modifier.width(110.dp))
                PosterSkeleton(Modifier.width(110.dp))
            }
        }
    }
}
