package com.codingpit.muviss.core.testing

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.dp

/**
 * A stand-in for a phone's safe area.
 *
 * Skiko — the renderer behind `runComposeUiTest` — reports **zero** window
 * insets, so a desktop-rendered screenshot of an inset bug looks identical
 * before and after the fix. Everything that consumes insets therefore takes
 * them as a parameter, and tests pass this instead of `WindowInsets.safeDrawing`.
 *
 * The numbers are a Pixel-class device: a 48dp top band tall enough to cover a
 * status bar plus a camera cutout, and a 24dp gesture bar below.
 *
 * What this proves is that a layout *responds* to insets. Whether Android
 * reports the cutout correctly is only observable on a device.
 */
val TestSafeAreaInsets: WindowInsets = WindowInsets(left = 0.dp, top = 48.dp, right = 0.dp, bottom = 24.dp)
