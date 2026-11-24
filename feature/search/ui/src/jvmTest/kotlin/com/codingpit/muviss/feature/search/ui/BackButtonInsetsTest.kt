@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test

/**
 * The back control sits over immersive artwork and clears the top band it is given: the status bar, a caption bar and
 * a display cutout in production. Skiko reports zero insets, so the test passes a synthetic band instead; a device
 * reports the real one.
 */
class BackButtonInsetsTest {

    @Test
    fun the_back_control_clears_the_top_inset_plus_its_12_dp_margin() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                BackButton(onBack = {}, insets = WindowInsets(top = 48.dp))
            }
        }

        onNodeWithTag(BACK_BUTTON_TAG).assertTopPositionInRootIsEqualTo(60.dp)
    }
}
