package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.graphics.Color
import kotlin.math.pow

/**
 * The WCAG 2.x relative luminance, written out in full rather than read from
 * `Color.luminance()`, so the contrast tests check the number the accessibility
 * note reports, not Compose's implementation of it. Shared by the formula test
 * and the rendered-pixel measurement in jvmTest.
 */
internal fun wcagRelativeLuminance(c: Color): Double {
    fun linear(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)
}

/** WCAG contrast ratio between two opaque colours, always `>= 1`. */
internal fun wcagContrastRatio(a: Color, b: Color): Double {
    val la = wcagRelativeLuminance(a)
    val lb = wcagRelativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}
