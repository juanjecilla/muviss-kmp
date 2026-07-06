package com.codingpit.muviss.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF6C5CE7)
private val PurpleDark = Color(0xFFB4A7FF)

private val LightColors = lightColorScheme(
    primary = Purple,
    secondary = Color(0xFF00B894),
)

private val DarkColors = darkColorScheme(
    primary = PurpleDark,
    secondary = Color(0xFF55EFC4),
)

@Composable
fun MuvissTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
