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
 * [insets] is a parameter rather than a hard-coded read because Skiko — the
 * renderer behind `runComposeUiTest` — reports zero insets, so this would be
 * untestable off-device otherwise. Tests pass a synthetic value; production
 * uses the default.
 */
@Composable
fun ScreenInsets(
    insets: WindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    content: @Composable () -> Unit,
) {
    Box(Modifier.windowInsetsPadding(insets)) {
        content()
    }
}
