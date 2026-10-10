@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.codingpit.muviss.core.testing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.PlatformParagraphStyle
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
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
 *
 * So does the **content colour**. On a real screen a `Scaffold` (or a
 * `Surface`) sets `LocalContentColor` to `onBackground`, and every `Text`
 * without an explicit colour reads it. A bare `Box` sets nothing, so a
 * component rendered straight into this frame — a chart, a section — drew its
 * text in Compose's default black. In light mode that was close enough to go
 * unnoticed; in dark mode it was black text on a near-black frame, a golden of
 * something the app never shows. The frame provides `onBackground` the way a
 * screen would.
 *
 * What this frame cannot fix, and every golden test has to handle itself: the
 * **theme**. `MuvissTheme`'s `darkTheme` defaults to `isSystemInDarkTheme()`,
 * which on desktop is the host machine's setting — so a golden recorded on a
 * developer's machine in dark mode compares against a light render on
 * `ubuntu-latest` and 99.99% of pixels move. Every golden test must pass
 * `darkTheme` explicitly.
 *
 * What it does fix: **glyph rasterization**. The font is pinned (bundled
 * Schibsted Grotesk), but Skia's hinting is not — Compose picks it per OS
 * (`FontRasterizationSettings.PlatformDefault`: `Normal` on macOS, `Slight` on
 * Linux), so every glyph edge of a Mac-recorded golden moved on CI and
 * text-dense screens crossed the tolerance (#171). Every text style inside
 * this frame is pinned to [GOLDEN_RASTERIZATION], macOS's default, so goldens
 * recorded on a Mac stay valid and Linux renders to match.
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
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme,
            shapes = MaterialTheme.shapes,
            typography = MaterialTheme.typography.pinned(),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                ProvideTextStyle(LocalTextStyle.current.pinned(), content)
            }
        }
    }
}

/** macOS's `FontRasterizationSettings.PlatformDefault`, pinned for every host. */
val GOLDEN_RASTERIZATION = FontRasterizationSettings(
    smoothing = FontSmoothing.AntiAlias,
    hinting = FontHinting.None,
    subpixelPositioning = true,
    autoHintingForced = false,
)

private fun TextStyle.pinned(): TextStyle = copy(platformStyle = PlatformTextStyle(spanStyle = null, paragraphStyle = PlatformParagraphStyle(GOLDEN_RASTERIZATION)))

private fun Typography.pinned(): Typography = Typography(
    displayLarge = displayLarge.pinned(),
    displayMedium = displayMedium.pinned(),
    displaySmall = displaySmall.pinned(),
    headlineLarge = headlineLarge.pinned(),
    headlineMedium = headlineMedium.pinned(),
    headlineSmall = headlineSmall.pinned(),
    titleLarge = titleLarge.pinned(),
    titleMedium = titleMedium.pinned(),
    titleSmall = titleSmall.pinned(),
    bodyLarge = bodyLarge.pinned(),
    bodyMedium = bodyMedium.pinned(),
    bodySmall = bodySmall.pinned(),
    labelLarge = labelLarge.pinned(),
    labelMedium = labelMedium.pinned(),
    labelSmall = labelSmall.pinned(),
)
