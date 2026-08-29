package com.codingpit.muviss.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The palette extraction (EPIC 22) had exactly one requirement: change
 * nothing. Glance and the iOS widget needed these literals reachable outside
 * a Compose tree, and lifting thirty-odd hexes out of two scheme builders is
 * precisely the edit that quietly swaps two of them.
 *
 * The screenshot goldens are the other half of this check — they still pass
 * unrecorded — but a golden only covers the roles that happen to be on
 * screen. This covers every role in both schemes, spelled out rather than
 * compared against [MuvissPalette], so that reading the palette wrong cannot
 * make the test agree with itself.
 */
class MuvissPaletteTest {

    private fun rolesOf(scheme: ColorScheme): List<Pair<String, Color>> = listOf(
        "primary" to scheme.primary,
        "onPrimary" to scheme.onPrimary,
        "primaryContainer" to scheme.primaryContainer,
        "onPrimaryContainer" to scheme.onPrimaryContainer,
        "secondary" to scheme.secondary,
        "onSecondary" to scheme.onSecondary,
        "secondaryContainer" to scheme.secondaryContainer,
        "onSecondaryContainer" to scheme.onSecondaryContainer,
        "tertiary" to scheme.tertiary,
        "onTertiary" to scheme.onTertiary,
        "tertiaryContainer" to scheme.tertiaryContainer,
        "onTertiaryContainer" to scheme.onTertiaryContainer,
        "error" to scheme.error,
        "onError" to scheme.onError,
        "errorContainer" to scheme.errorContainer,
        "onErrorContainer" to scheme.onErrorContainer,
        "background" to scheme.background,
        "onBackground" to scheme.onBackground,
        "surface" to scheme.surface,
        "onSurface" to scheme.onSurface,
        "surfaceVariant" to scheme.surfaceVariant,
        "onSurfaceVariant" to scheme.onSurfaceVariant,
        "outline" to scheme.outline,
        "outlineVariant" to scheme.outlineVariant,
        "surfaceContainerLowest" to scheme.surfaceContainerLowest,
        "surfaceContainerLow" to scheme.surfaceContainerLow,
        "surfaceContainer" to scheme.surfaceContainer,
        "surfaceContainerHigh" to scheme.surfaceContainerHigh,
        "surfaceContainerHighest" to scheme.surfaceContainerHighest,
        "inverseSurface" to scheme.inverseSurface,
        "inverseOnSurface" to scheme.inverseOnSurface,
        "inversePrimary" to scheme.inversePrimary,
        "scrim" to scheme.scrim,
    )

    @Test
    fun the_dark_scheme_carries_the_design_system_values() {
        assertEquals(
            listOf(
                "primary" to Color(0xFFFFCB6B),
                "onPrimary" to Color(0xFF432C00),
                "primaryContainer" to Color(0xFF614000),
                "onPrimaryContainer" to Color(0xFFFFDDA6),
                "secondary" to Color(0xFFD8C4A2),
                "onSecondary" to Color(0xFF3A2E12),
                "secondaryContainer" to Color(0xFF524526),
                "onSecondaryContainer" to Color(0xFFF5E0BD),
                "tertiary" to Color(0xFF9CD3C0),
                "onTertiary" to Color(0xFF003829),
                "tertiaryContainer" to Color(0xFF1F4D3E),
                "onTertiaryContainer" to Color(0xFFB8EFDB),
                "error" to Color(0xFFFFB4AB),
                "onError" to Color(0xFF690005),
                "errorContainer" to Color(0xFF93000A),
                "onErrorContainer" to Color(0xFFFFDAD6),
                "background" to Color(0xFF14120E),
                "onBackground" to Color(0xFFE9E2D4),
                "surface" to Color(0xFF14120E),
                "onSurface" to Color(0xFFE9E2D4),
                "surfaceVariant" to Color(0xFF4C463A),
                "onSurfaceVariant" to Color(0xFFCFC6B4),
                "outline" to Color(0xFF988F7E),
                "outlineVariant" to Color(0xFF4C463A),
                "surfaceContainerLowest" to Color(0xFF0E0D0A),
                "surfaceContainerLow" to Color(0xFF1C1A15),
                "surfaceContainer" to Color(0xFF211E18),
                "surfaceContainerHigh" to Color(0xFF2B2822),
                "surfaceContainerHighest" to Color(0xFF35322C),
                "inverseSurface" to Color(0xFFE9E2D4),
                "inverseOnSurface" to Color(0xFF322F28),
                "inversePrimary" to Color(0xFF7C5800),
                "scrim" to Color(0xFF000000),
            ),
            rolesOf(DarkColors),
        )
    }

    @Test
    fun the_light_scheme_carries_the_design_system_values() {
        assertEquals(
            listOf(
                "primary" to Color(0xFF7C5800),
                "onPrimary" to Color(0xFFFFFFFF),
                "primaryContainer" to Color(0xFFFFDDA6),
                "onPrimaryContainer" to Color(0xFF271900),
                "secondary" to Color(0xFF6C5D3F),
                "onSecondary" to Color(0xFFFFFFFF),
                "secondaryContainer" to Color(0xFFF5E0BD),
                "onSecondaryContainer" to Color(0xFF241A04),
                "tertiary" to Color(0xFF386659),
                "onTertiary" to Color(0xFFFFFFFF),
                "tertiaryContainer" to Color(0xFFB8EFDB),
                "onTertiaryContainer" to Color(0xFF002018),
                "error" to Color(0xFFBA1A1A),
                "onError" to Color(0xFFFFFFFF),
                "errorContainer" to Color(0xFFFFDAD6),
                "onErrorContainer" to Color(0xFF410002),
                "background" to Color(0xFFFDF8EF),
                "onBackground" to Color(0xFF1D1B14),
                "surface" to Color(0xFFFDF8EF),
                "onSurface" to Color(0xFF1D1B14),
                "surfaceVariant" to Color(0xFFECE1CC),
                "onSurfaceVariant" to Color(0xFF4C463A),
                "outline" to Color(0xFF7E7667),
                "outlineVariant" to Color(0xFFCFC6B4),
                "surfaceContainerLowest" to Color(0xFFFFFFFF),
                "surfaceContainerLow" to Color(0xFFF8F2E4),
                "surfaceContainer" to Color(0xFFF2ECDE),
                "surfaceContainerHigh" to Color(0xFFECE6D9),
                "surfaceContainerHighest" to Color(0xFFE6E1D3),
                "inverseSurface" to Color(0xFF322F28),
                "inverseOnSurface" to Color(0xFFF5EFE1),
                "inversePrimary" to Color(0xFFFFCB6B),
                "scrim" to Color(0xFF000000),
            ),
            rolesOf(LightColors),
        )
    }
}
