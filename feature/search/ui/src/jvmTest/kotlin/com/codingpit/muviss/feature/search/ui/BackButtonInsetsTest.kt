@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.layout.ScreenInsets
import com.codingpit.muviss.core.designsystem.layout.extendIntoTopInset
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.TestSafeAreaInsets
import kotlin.test.Test

/**
 * The back control sits over immersive artwork and clears the top band it is given: the status bar, a caption bar and
 * a display cutout in production. Skiko reports zero insets, so the tests pass a synthetic band instead; a device
 * reports the real one.
 */
class BackButtonInsetsTest {

    @Test
    fun the_back_control_clears_the_top_inset_plus_its_12_dp_margin() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                BackButton(onBack = {}, topInset = 48.dp)
            }
        }

        onNodeWithTag(BACK_BUTTON_TAG).assertTopPositionInRootIsEqualTo(60.dp)
    }

    /**
     * The production shape: [ScreenInsets] pads the band, the screen extends up into it, and its back control is given
     * the raw band. Had the control read the inset through an inset modifier, the wrapper's consumed inset would
     * make it land at 12 dp, under the status bar.
     */
    @Test
    fun an_extended_screen_places_its_back_control_below_the_wrapper_band() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                ScreenInsets(insets = TestSafeAreaInsets) {
                    Box(Modifier.extendIntoTopInset(48.dp).fillMaxSize().testTag(SCREEN_TAG)) {
                        BackButton(onBack = {}, topInset = 48.dp)
                    }
                }
            }
        }

        onNodeWithTag(SCREEN_TAG).assertTopPositionInRootIsEqualTo(0.dp)
        onNodeWithTag(BACK_BUTTON_TAG).assertTopPositionInRootIsEqualTo(60.dp)
    }

    private companion object {
        const val SCREEN_TAG = "backButtonScreen"
    }
}
