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
 *
 * These literals are public, and were made so by the home-screen widgets
 * (EPIC 22). A Glance widget cannot use [MuvissTheme]: it is a `MaterialTheme`
 * wrapper, and Glance composes against `GlanceTheme` with its own
 * `ColorProviders`. The choice was one source of colour or two, and the amber
 * identity is the point of the widget looking like Muviss rather than like a
 * system list — so the schemes below are assembled from here, and Glance
 * builds its providers from the same values. The iOS widget's asset catalog
 * carries the same hexes.
 *
 * Consumers inside a Compose tree should still read `MaterialTheme.colorScheme`
 * rather than reaching in here — the theme resolves light and dark for them.
 * This exists for the surfaces that have no theme to ask.
 */
object MuvissPalette {

    /** The dark scheme's roles, in M3 order. */
    object Dark {
        val primary = Color(0xFFFFCB6B)
        val onPrimary = Color(0xFF432C00)
        val primaryContainer = Color(0xFF614000)
        val onPrimaryContainer = Color(0xFFFFDDA6)
        val secondary = Color(0xFFD8C4A2)
        val onSecondary = Color(0xFF3A2E12)
        val secondaryContainer = Color(0xFF524526)
        val onSecondaryContainer = Color(0xFFF5E0BD)
        val tertiary = Color(0xFF9CD3C0)
        val onTertiary = Color(0xFF003829)
        val tertiaryContainer = Color(0xFF1F4D3E)
        val onTertiaryContainer = Color(0xFFB8EFDB)
        val error = Color(0xFFFFB4AB)
        val onError = Color(0xFF690005)
        val errorContainer = Color(0xFF93000A)
        val onErrorContainer = Color(0xFFFFDAD6)
        val background = Color(0xFF14120E)
        val onBackground = Color(0xFFE9E2D4)
        val surface = Color(0xFF14120E)
        val onSurface = Color(0xFFE9E2D4)
        val surfaceVariant = Color(0xFF4C463A)
        val onSurfaceVariant = Color(0xFFCFC6B4)
        val outline = Color(0xFF988F7E)
        val outlineVariant = Color(0xFF4C463A)
        val surfaceContainerLowest = Color(0xFF0E0D0A)
        val surfaceContainerLow = Color(0xFF1C1A15)
        val surfaceContainer = Color(0xFF211E18)
        val surfaceContainerHigh = Color(0xFF2B2822)
        val surfaceContainerHighest = Color(0xFF35322C)
        val inverseSurface = Color(0xFFE9E2D4)
        val inverseOnSurface = Color(0xFF322F28)
        val inversePrimary = Color(0xFF7C5800)
        val scrim = Color(0xFF000000)
    }

    /** The light scheme's roles, in M3 order. */
    object Light {
        val primary = Color(0xFF7C5800)
        val onPrimary = Color(0xFFFFFFFF)
        val primaryContainer = Color(0xFFFFDDA6)
        val onPrimaryContainer = Color(0xFF271900)
        val secondary = Color(0xFF6C5D3F)
        val onSecondary = Color(0xFFFFFFFF)
        val secondaryContainer = Color(0xFFF5E0BD)
        val onSecondaryContainer = Color(0xFF241A04)
        val tertiary = Color(0xFF386659)
        val onTertiary = Color(0xFFFFFFFF)
        val tertiaryContainer = Color(0xFFB8EFDB)
        val onTertiaryContainer = Color(0xFF002018)
        val error = Color(0xFFBA1A1A)
        val onError = Color(0xFFFFFFFF)
        val errorContainer = Color(0xFFFFDAD6)
        val onErrorContainer = Color(0xFF410002)
        val background = Color(0xFFFDF8EF)
        val onBackground = Color(0xFF1D1B14)
        val surface = Color(0xFFFDF8EF)
        val onSurface = Color(0xFF1D1B14)
        val surfaceVariant = Color(0xFFECE1CC)
        val onSurfaceVariant = Color(0xFF4C463A)
        val outline = Color(0xFF7E7667)
        val outlineVariant = Color(0xFFCFC6B4)
        val surfaceContainerLowest = Color(0xFFFFFFFF)
        val surfaceContainerLow = Color(0xFFF8F2E4)
        val surfaceContainer = Color(0xFFF2ECDE)
        val surfaceContainerHigh = Color(0xFFECE6D9)
        val surfaceContainerHighest = Color(0xFFE6E1D3)
        val inverseSurface = Color(0xFF322F28)
        val inverseOnSurface = Color(0xFFF5EFE1)
        val inversePrimary = Color(0xFFFFCB6B)
        val scrim = Color(0xFF000000)
    }
}

internal val DarkColors = darkColorScheme(
    primary = MuvissPalette.Dark.primary,
    onPrimary = MuvissPalette.Dark.onPrimary,
    primaryContainer = MuvissPalette.Dark.primaryContainer,
    onPrimaryContainer = MuvissPalette.Dark.onPrimaryContainer,
    secondary = MuvissPalette.Dark.secondary,
    onSecondary = MuvissPalette.Dark.onSecondary,
    secondaryContainer = MuvissPalette.Dark.secondaryContainer,
    onSecondaryContainer = MuvissPalette.Dark.onSecondaryContainer,
    tertiary = MuvissPalette.Dark.tertiary,
    onTertiary = MuvissPalette.Dark.onTertiary,
    tertiaryContainer = MuvissPalette.Dark.tertiaryContainer,
    onTertiaryContainer = MuvissPalette.Dark.onTertiaryContainer,
    error = MuvissPalette.Dark.error,
    onError = MuvissPalette.Dark.onError,
    errorContainer = MuvissPalette.Dark.errorContainer,
    onErrorContainer = MuvissPalette.Dark.onErrorContainer,
    background = MuvissPalette.Dark.background,
    onBackground = MuvissPalette.Dark.onBackground,
    surface = MuvissPalette.Dark.surface,
    onSurface = MuvissPalette.Dark.onSurface,
    surfaceVariant = MuvissPalette.Dark.surfaceVariant,
    onSurfaceVariant = MuvissPalette.Dark.onSurfaceVariant,
    outline = MuvissPalette.Dark.outline,
    outlineVariant = MuvissPalette.Dark.outlineVariant,
    surfaceContainerLowest = MuvissPalette.Dark.surfaceContainerLowest,
    surfaceContainerLow = MuvissPalette.Dark.surfaceContainerLow,
    surfaceContainer = MuvissPalette.Dark.surfaceContainer,
    surfaceContainerHigh = MuvissPalette.Dark.surfaceContainerHigh,
    surfaceContainerHighest = MuvissPalette.Dark.surfaceContainerHighest,
    inverseSurface = MuvissPalette.Dark.inverseSurface,
    inverseOnSurface = MuvissPalette.Dark.inverseOnSurface,
    inversePrimary = MuvissPalette.Dark.inversePrimary,
    scrim = MuvissPalette.Dark.scrim,
)

internal val LightColors = lightColorScheme(
    primary = MuvissPalette.Light.primary,
    onPrimary = MuvissPalette.Light.onPrimary,
    primaryContainer = MuvissPalette.Light.primaryContainer,
    onPrimaryContainer = MuvissPalette.Light.onPrimaryContainer,
    secondary = MuvissPalette.Light.secondary,
    onSecondary = MuvissPalette.Light.onSecondary,
    secondaryContainer = MuvissPalette.Light.secondaryContainer,
    onSecondaryContainer = MuvissPalette.Light.onSecondaryContainer,
    tertiary = MuvissPalette.Light.tertiary,
    onTertiary = MuvissPalette.Light.onTertiary,
    tertiaryContainer = MuvissPalette.Light.tertiaryContainer,
    onTertiaryContainer = MuvissPalette.Light.onTertiaryContainer,
    error = MuvissPalette.Light.error,
    onError = MuvissPalette.Light.onError,
    errorContainer = MuvissPalette.Light.errorContainer,
    onErrorContainer = MuvissPalette.Light.onErrorContainer,
    background = MuvissPalette.Light.background,
    onBackground = MuvissPalette.Light.onBackground,
    surface = MuvissPalette.Light.surface,
    onSurface = MuvissPalette.Light.onSurface,
    surfaceVariant = MuvissPalette.Light.surfaceVariant,
    onSurfaceVariant = MuvissPalette.Light.onSurfaceVariant,
    outline = MuvissPalette.Light.outline,
    outlineVariant = MuvissPalette.Light.outlineVariant,
    surfaceContainerLowest = MuvissPalette.Light.surfaceContainerLowest,
    surfaceContainerLow = MuvissPalette.Light.surfaceContainerLow,
    surfaceContainer = MuvissPalette.Light.surfaceContainer,
    surfaceContainerHigh = MuvissPalette.Light.surfaceContainerHigh,
    surfaceContainerHighest = MuvissPalette.Light.surfaceContainerHighest,
    inverseSurface = MuvissPalette.Light.inverseSurface,
    inverseOnSurface = MuvissPalette.Light.inverseOnSurface,
    inversePrimary = MuvissPalette.Light.inversePrimary,
    scrim = MuvissPalette.Light.scrim,
)

/**
 * Categorical palette for the hand-drawn Canvas charts (profile stats).
 * Order matters: amber (primary data), sage, blue, sand, rose, violet —
 * matched to the design doc's donut/bar mockups. Charts consume colors from
 * here so chart styling stays with the theme without the design system
 * knowing any chart types.
 *
 * There are **six** entries because the genre donut draws up to
 * `MAX_GENRE_SLOTS = 6` slices. With exactly six genres in the library no
 * fold to "Other" happens, so all six are real and a five-color list made
 * slice 6 wrap to `palette[0]` — the same amber as slice 1. The last three
 * are literals rather than theme roles: the schemes have no further role
 * that reads as a distinct data series in both light and dark.
 */
object MuvissChartPalette {
    @Composable
    fun categorical(): List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        Color(0xFF6B93C9),
        Color(0xFFD8C4A2),
        Color(0xFFC98B9A),
        Color(0xFF9B8AC4),
    )
}
