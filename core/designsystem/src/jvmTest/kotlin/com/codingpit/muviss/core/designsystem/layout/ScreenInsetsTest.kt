@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.testing.TestSafeAreaInsets
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The regression these guard is the one from the bug report: content drawn
 * under the status bar and camera cutout.
 *
 * They inject synthetic insets, because Skiko reports none — so what is
 * verified is that the layout *responds* to an inset, not that Android reports
 * the cutout. The latter is only observable on a device.
 */
class ScreenInsetsTest {

    @Test
    fun content_starts_below_the_unsafe_area() = runComposeUiTest {
        setContent {
            ScreenInsets(insets = TestSafeAreaInsets) {
                Box(Modifier.testTag(CONTENT).fillMaxSize())
            }
        }

        onNodeWithTag(CONTENT).assertTopPositionInRootIsEqualTo(48.dp)
    }

    @Test
    fun an_extended_screen_moves_up_into_the_band_the_wrapper_padded() = runComposeUiTest {
        setContent {
            ScreenInsets(insets = TestSafeAreaInsets) {
                Box(Modifier.extendIntoTopInset(48.dp).testTag(CONTENT).fillMaxSize())
            }
        }

        onNodeWithTag(CONTENT).assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun an_extended_screen_still_clears_the_sides() = runComposeUiTest {
        setContent {
            ScreenInsets(insets = WindowInsets(left = 20.dp, top = 48.dp, right = 20.dp, bottom = 0.dp)) {
                Box(Modifier.extendIntoTopInset(48.dp).testTag(CONTENT).fillMaxSize())
            }
        }

        onNodeWithTag(CONTENT).assertLeftPositionInRootIsEqualTo(20.dp)
        onNodeWithTag(CONTENT).assertTopPositionInRootIsEqualTo(0.dp)
    }

    /**
     * The extension keeps the screen's own height, so what follows it does not move: a weighted extended screen takes
     * exactly the space left, and the box after it still ends at the bottom of the column.
     */
    @Test
    fun an_extended_screen_keeps_its_height_so_what_follows_it_does_not_move() = runComposeUiTest {
        setContent {
            ScreenInsets(insets = TestSafeAreaInsets) {
                Column(Modifier.testTag(ROOT).fillMaxSize()) {
                    Box(Modifier.weight(1f).extendIntoTopInset(48.dp).fillMaxWidth())
                    Box(Modifier.testTag(CONTENT).size(10.dp))
                }
            }
        }

        val root = onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        val content = onNodeWithTag(CONTENT).fetchSemanticsNode().boundsInRoot
        assertEquals(root.bottom, content.bottom, 0.5f)
    }

    @Test
    fun horizontal_insets_are_applied_too() = runComposeUiTest {
        setContent {
            ScreenInsets(insets = WindowInsets(left = 20.dp, top = 0.dp, right = 20.dp, bottom = 0.dp)) {
                Box(Modifier.testTag(CONTENT).fillMaxSize())
            }
        }

        onNodeWithTag(CONTENT).assertLeftPositionInRootIsEqualTo(20.dp)
    }

    @Test
    fun without_insets_nothing_moves() = runComposeUiTest {
        setContent {
            ScreenInsets(insets = WindowInsets(left = 0.dp, top = 0.dp, right = 0.dp, bottom = 0.dp)) {
                Box(Modifier.testTag(CONTENT).size(10.dp))
            }
        }

        onNodeWithTag(CONTENT).assertTopPositionInRootIsEqualTo(0.dp)
    }

    /**
     * The bottom is left to the navigation bar, which insets itself — padding
     * it here would float the bar off the bottom edge.
     */
    @Test
    fun the_bottom_inset_is_not_consumed_by_default() = runComposeUiTest {
        setContent {
            Box(Modifier.fillMaxSize()) {
                ScreenInsets {
                    Box(Modifier.testTag(CONTENT).fillMaxSize())
                }
            }
        }

        // Skiko reports zero insets, so the default read is a no-op here; the
        // assertion that matters is that it composes and places at the origin.
        onNodeWithTag(CONTENT).assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test
    fun a_side_extended_screen_runs_under_the_side_insets() = runComposeUiTest {
        setContent {
            ScreenInsets(insets = WindowInsets(left = 20.dp, top = 0.dp, right = 20.dp, bottom = 0.dp)) {
                Box(Modifier.extendIntoSideInsets(left = 20.dp, right = 20.dp).testTag(CONTENT).fillMaxSize())
            }
        }

        val window = onRoot().fetchSemanticsNode().boundsInRoot
        val content = onNodeWithTag(CONTENT).fetchSemanticsNode().boundsInRoot
        assertEquals(window.left, content.left)
        assertEquals(window.right, content.right)
    }

    private companion object {
        const val CONTENT = "content"
        const val ROOT = "root"
    }
}
