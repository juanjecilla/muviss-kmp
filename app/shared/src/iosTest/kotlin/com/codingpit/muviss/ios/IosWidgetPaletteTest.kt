package com.codingpit.muviss.ios

import androidx.compose.ui.graphics.Color
import com.codingpit.muviss.core.designsystem.theme.MuvissPalette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IosWidgetPaletteTest {

    @Test
    fun dark_background_is_the_palette_background_as_argb() {
        assertEquals(0xFF14120E.toInt(), IosWidgetPalette.argb(IosWidgetPalette.BACKGROUND, dark = true))
    }

    @Test
    fun light_surface_is_the_row_card_tone_android_uses() {
        assertEquals(0xFFECE1CC.toInt(), IosWidgetPalette.argb(IosWidgetPalette.SURFACE, dark = false))
    }

    @Test
    fun dark_surface_is_the_row_card_tone_android_uses() {
        assertEquals(0xFF4C463A.toInt(), IosWidgetPalette.argb(IosWidgetPalette.SURFACE, dark = true))
    }

    @Test
    fun primary_is_amber_in_both_schemes_with_its_own_on_colour() {
        assertEquals(0xFFFFCB6B.toInt(), IosWidgetPalette.argb(IosWidgetPalette.PRIMARY, dark = true))
        assertEquals(0xFF7C5800.toInt(), IosWidgetPalette.argb(IosWidgetPalette.PRIMARY, dark = false))
        assertEquals(0xFF432C00.toInt(), IosWidgetPalette.argb(IosWidgetPalette.ON_PRIMARY, dark = true))
        assertEquals(0xFFFFFFFF.toInt(), IosWidgetPalette.argb(IosWidgetPalette.ON_PRIMARY, dark = false))
    }

    @Test
    fun every_role_reads_the_same_value_the_palette_declares() {
        val expected = mapOf(
            IosWidgetPalette.BACKGROUND to MuvissPalette.Light.background,
            IosWidgetPalette.ON_SURFACE to MuvissPalette.Light.onSurface,
            IosWidgetPalette.ON_SURFACE_VARIANT to MuvissPalette.Light.onSurfaceVariant,
        )

        expected.forEach { (role, color) ->
            assertEquals(color.toArgbInt(), IosWidgetPalette.argb(role, dark = false), "role $role")
        }
    }

    @Test
    fun unknown_role_fails_loudly_rather_than_drawing_a_wrong_colour() {
        assertFailsWith<IllegalArgumentException> {
            IosWidgetPalette.argb("tertiary", dark = true)
        }
    }

    @Test
    fun conversion_rounds_each_channel_rather_than_truncating() {
        // 0.999f * 255 = 254.75, which truncation would store as 254 and rounding as 255.
        val argb = Color(red = 0.999f, green = 0f, blue = 0f, alpha = 1f).toArgbInt()

        assertEquals(0xFF, (argb shr 16) and 0xFF)
    }
}
