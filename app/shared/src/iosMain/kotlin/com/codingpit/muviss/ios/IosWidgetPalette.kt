package com.codingpit.muviss.ios

import androidx.compose.ui.graphics.Color
import com.codingpit.muviss.core.designsystem.theme.MuvissPalette
import kotlin.math.roundToInt

/**
 * The colours the iOS widget draws, read from [MuvissPalette] and handed to
 * SwiftUI as ARGB, so the Swift side keeps no colour literals of its own.
 *
 * The widget used to carry copies of the hexes, kept honest only by a comment.
 * Now there is one source, and the Android widget reads the same one through
 * `muvissColorScheme`. The roles are the ones the widget uses, and the light
 * scheme is included because the widget follows the system appearance like
 * Android's does.
 */
object IosWidgetPalette {

    /** Role names the Swift side passes. Constants so a typo is a compile error here, not a silent wrong colour. */
    const val BACKGROUND = "background"
    const val SURFACE = "surface"
    const val ON_SURFACE = "onSurface"
    const val ON_SURFACE_VARIANT = "onSurfaceVariant"
    const val PRIMARY = "primary"
    const val ON_PRIMARY = "onPrimary"

    /** The ARGB value of [role] in the dark or light scheme. Throws on an unknown role, which is a programming error. */
    fun argb(role: String, dark: Boolean): Int = colorOf(role, dark).toArgbInt()

    internal fun colorOf(role: String, dark: Boolean): Color {
        val scheme = if (dark) darkRoles else lightRoles
        return when (role) {
            BACKGROUND -> scheme.background
            SURFACE -> scheme.surface
            ON_SURFACE -> scheme.onSurface
            ON_SURFACE_VARIANT -> scheme.onSurfaceVariant
            PRIMARY -> scheme.primary
            ON_PRIMARY -> scheme.onPrimary
            else -> throw IllegalArgumentException("Unknown widget colour role: $role")
        }
    }

    /** `surface` is the row card: `surfaceVariant` in both schemes, the role Android's widget row uses (`MuvissWidget.kt`). */
    private class Roles(
        val background: Color,
        val surface: Color,
        val onSurface: Color,
        val onSurfaceVariant: Color,
        val primary: Color,
        val onPrimary: Color,
    )

    private val darkRoles = Roles(
        background = MuvissPalette.Dark.background,
        surface = MuvissPalette.Dark.surfaceVariant,
        onSurface = MuvissPalette.Dark.onSurface,
        onSurfaceVariant = MuvissPalette.Dark.onSurfaceVariant,
        primary = MuvissPalette.Dark.primary,
        onPrimary = MuvissPalette.Dark.onPrimary,
    )

    private val lightRoles = Roles(
        background = MuvissPalette.Light.background,
        surface = MuvissPalette.Light.surfaceVariant,
        onSurface = MuvissPalette.Light.onSurface,
        onSurfaceVariant = MuvissPalette.Light.onSurfaceVariant,
        primary = MuvissPalette.Light.primary,
        onPrimary = MuvissPalette.Light.onPrimary,
    )
}

/**
 * `Color.toArgb()` is an Android-only extension, so this does the same
 * conversion by hand. Rounding rather than truncating, so that a channel stored
 * as `0.2f` cannot come out one step low.
 */
internal fun Color.toArgbInt(): Int {
    fun channel(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 255)
    return (channel(alpha) shl 24) or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}
