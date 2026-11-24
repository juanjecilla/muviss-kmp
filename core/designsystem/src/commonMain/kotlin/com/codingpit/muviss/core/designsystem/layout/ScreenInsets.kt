package com.codingpit.muviss.core.designsystem.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The top band that artwork drawn under the status bar must grow by, and that controls over it must clear.
 *
 * `safeDrawing` rather than `statusBars`: a caption bar on a freeform Android window occupies the top band without
 * a status-bar inset, and `statusBars` does not cover it. Display cutouts are included for the same reason as
 * [ScreenInsets].
 */
@Composable
fun immersiveTopInsets(): WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)

/**
 * Holds app content clear of the system's unsafe area.
 *
 * The app runs edge-to-edge (`enableEdgeToEdge()`, and `targetSdk = 36` forces
 * it regardless), so nothing insets content unless something asks. The shared
 * root is `NavigationSuiteScaffold`, whose content lambda hands out **no**
 * `PaddingValues` and which takes no `contentWindowInsets` — so every screen
 * that isn't built on an M3 `Scaffold` drew its first pixel at y=0, under the
 * status bar and camera cutout. This is the one place that fixes that, for
 * every screen and every platform at once.
 *
 * [safeDrawing] rather than `systemBars`: it includes `displayCutout`, and a
 * cutout is exactly what a modern phone puts over the top of the screen. The
 * bottom is deliberately left out — the navigation bar and rail inset
 * themselves, and padding it here would leave a gap under them.
 *
 * [Modifier.windowInsetsPadding] **consumes** what it applies, so a descendant
 * `Scaffold` sees what is left rather than padding the same band a second time.
 * No explicit `consumeWindowInsets` is needed.
 *
 * [immersive] leaves the top alone for a screen whose hero artwork draws under the status bar
 * (Detail, Episode Detail). Such a screen owns its top inset: it grows the image by it and pads
 * its own controls. Without this the band above the image shows the theme background, which
 * reads as a black bar in dark theme.
 *
 * [insets] is a parameter rather than a hard-coded read because Skiko — the
 * renderer behind `runComposeUiTest` — reports zero insets, so this would be
 * untestable off-device otherwise. Tests pass a synthetic value; production
 * uses the default.
 */
@Composable
fun ScreenInsets(
    immersive: Boolean = false,
    insets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    content: @Composable () -> Unit,
) {
    val applied = if (immersive) insets.only(WindowInsetsSides.Horizontal) else insets
    Box(Modifier.windowInsetsPadding(applied)) {
        content()
    }
}
