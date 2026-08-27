@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.TestSafeAreaInsets
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlin.test.Test

/**
 * The inset seam, as a picture.
 *
 * A coloured block filling the content area makes the safe band obvious: the
 * "with" golden has a 48dp strip of background above the block, the "without"
 * golden has none. If [ScreenInsets] ever stops applying what it is given,
 * these two images become identical.
 */
class ScreenInsetsGoldenTest {

    @Test
    fun insets_hold_content_clear_of_the_unsafe_area() = runComposeUiTest {
        setContent { InsetSample(TestSafeAreaInsets) }
        assertMatchesGolden("screen-insets-with")
    }

    @Test
    fun no_insets_means_no_offset() = runComposeUiTest {
        setContent { InsetSample(WindowInsets(left = 0.dp, top = 0.dp, right = 0.dp, bottom = 0.dp)) }
        assertMatchesGolden("screen-insets-without")
    }
}

@androidx.compose.runtime.Composable
private fun InsetSample(insets: WindowInsets) {
    // Pinned, never left to default: `MuvissTheme`'s `darkTheme` reads
    // `isSystemInDarkTheme()`, i.e. the *host's* setting, so a golden recorded
    // on a machine in dark mode compares against a light render on CI and every
    // pixel moves. See `GoldenSurface`.
    MuvissTheme(darkTheme = false) {
        GoldenSurface(width = 300.dp, height = 300.dp) {
            ScreenInsets(insets = insets) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Text("Content", color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}
