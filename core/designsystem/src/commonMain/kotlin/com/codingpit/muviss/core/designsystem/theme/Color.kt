package com.codingpit.muviss.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Muviss color tokens — "warm near-black + Marquee Amber" (Design System v1.0).
 *
 * The neutral is a warm near-black (a hint of amber, never blue-grey) so
 * poster art reads untinted. Amber carries every primary action; the sage
 * tertiary marks "watched/complete" so completion never collides with the
 * action color. All ~30 M3 roles are pinned for both schemes — never rely on
 * the M3 baseline defaults, they are blue-grey.
 */
internal val DarkColors = darkColorScheme(
    primary = Color(0xFFFFCB6B),
    onPrimary = Color(0xFF432C00),
    primaryContainer = Color(0xFF614000),
    onPrimaryContainer = Color(0xFFFFDDA6),
    secondary = Color(0xFFD8C4A2),
    onSecondary = Color(0xFF3A2E12),
    secondaryContainer = Color(0xFF524526),
    onSecondaryContainer = Color(0xFFF5E0BD),
    tertiary = Color(0xFF9CD3C0),
    onTertiary = Color(0xFF003829),
    tertiaryContainer = Color(0xFF1F4D3E),
    onTertiaryContainer = Color(0xFFB8EFDB),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF14120E),
    onBackground = Color(0xFFE9E2D4),
    surface = Color(0xFF14120E),
    onSurface = Color(0xFFE9E2D4),
    surfaceVariant = Color(0xFF4C463A),
    onSurfaceVariant = Color(0xFFCFC6B4),
    outline = Color(0xFF988F7E),
    outlineVariant = Color(0xFF4C463A),
    surfaceContainerLowest = Color(0xFF0E0D0A),
    surfaceContainerLow = Color(0xFF1C1A15),
    surfaceContainer = Color(0xFF211E18),
    surfaceContainerHigh = Color(0xFF2B2822),
    surfaceContainerHighest = Color(0xFF35322C),
    inverseSurface = Color(0xFFE9E2D4),
    inverseOnSurface = Color(0xFF322F28),
    inversePrimary = Color(0xFF7C5800),
    scrim = Color(0xFF000000),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF7C5800),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDA6),
    onPrimaryContainer = Color(0xFF271900),
    secondary = Color(0xFF6C5D3F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5E0BD),
    onSecondaryContainer = Color(0xFF241A04),
    tertiary = Color(0xFF386659),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB8EFDB),
    onTertiaryContainer = Color(0xFF002018),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFDF8EF),
    onBackground = Color(0xFF1D1B14),
    surface = Color(0xFFFDF8EF),
    onSurface = Color(0xFF1D1B14),
    surfaceVariant = Color(0xFFECE1CC),
    onSurfaceVariant = Color(0xFF4C463A),
    outline = Color(0xFF7E7667),
    outlineVariant = Color(0xFFCFC6B4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F2E4),
    surfaceContainer = Color(0xFFF2ECDE),
    surfaceContainerHigh = Color(0xFFECE6D9),
    surfaceContainerHighest = Color(0xFFE6E1D3),
    inverseSurface = Color(0xFF322F28),
    inverseOnSurface = Color(0xFFF5EFE1),
    inversePrimary = Color(0xFFFFCB6B),
    scrim = Color(0xFF000000),
)

/**
 * Categorical palette for the hand-drawn Canvas charts (profile stats).
 * Order matters: amber (primary data), sage, blue, sand, rose — matched to
 * the design doc's donut/bar mockups. Charts consume colors from here so
 * chart styling stays with the theme without the design system knowing any
 * chart types.
 */
object MuvissChartPalette {
    @Composable
    fun categorical(): List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        Color(0xFF6B93C9),
        Color(0xFFD8C4A2),
        Color(0xFFC98B9A),
    )
}
