package com.codingpit.muviss.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun MuvissTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = muvissColorScheme(darkTheme),
        typography = muvissTypography(),
        shapes = MuvissShapes,
        content = content,
    )
}

/**
 * The M3 scheme [MuvissTheme] installs, reachable without a Compose tree.
 *
 * Exists for the Android home-screen widget (EPIC 22), which composes against
 * `GlanceTheme` rather than `MaterialTheme` and so cannot be wrapped in
 * [MuvissTheme] — Glance builds its `ColorProviders` from these two schemes
 * instead, which is why the widget is the same amber as the app rather than
 * the system's wallpaper colours. Anything inside a Compose tree should keep
 * reading `MaterialTheme.colorScheme`; this is for surfaces with no theme to
 * ask. See [MuvissPalette] for the literals behind both.
 */
fun muvissColorScheme(darkTheme: Boolean): ColorScheme = if (darkTheme) DarkColors else LightColors
