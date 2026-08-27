package com.codingpit.muviss.core.testing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Identifies the fixed-size frame a golden is captured from. */
const val GOLDEN_SURFACE_TAG = "golden-surface"

/**
 * A fixed frame to render a golden into.
 *
 * The test window's own size is not something a golden can rely on, so every
 * screenshot is taken of this node instead of the root: same dimensions on
 * every machine, whatever the host decides the window should be. The default is
 * a phone in portrait — the form factor every screen here is designed for.
 *
 * The opaque background matters as much as the size. Captured transparency
 * differs between backends, and a golden of a half-transparent page compares
 * noise.
 */
@Composable
fun GoldenSurface(
    width: Dp = 412.dp,
    height: Dp = 892.dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .testTag(GOLDEN_SURFACE_TAG)
            .size(width, height)
            .background(MaterialTheme.colorScheme.background),
    ) {
        content()
    }
}
