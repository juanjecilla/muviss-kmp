package com.codingpit.muviss.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.codingpit.muviss.core.designsystem.generated.resources.Res
import com.codingpit.muviss.core.designsystem.generated.resources.SchibstedGrotesk_Bold
import com.codingpit.muviss.core.designsystem.generated.resources.SchibstedGrotesk_Medium
import com.codingpit.muviss.core.designsystem.generated.resources.SchibstedGrotesk_Regular
import com.codingpit.muviss.core.designsystem.generated.resources.SchibstedGrotesk_SemiBold
import org.jetbrains.compose.resources.Font

/**
 * Schibsted Grotesk (OFL 1.1), bundled — one family for the entire app,
 * weights 400/500/600/700. If the resource is ever stripped, Compose falls
 * back to the platform sans-serif per style's fallback chain.
 */
@Composable
fun schibstedGrotesk(): FontFamily = FontFamily(
    Font(Res.font.SchibstedGrotesk_Regular, FontWeight.Normal),
    Font(Res.font.SchibstedGrotesk_Medium, FontWeight.Medium),
    Font(Res.font.SchibstedGrotesk_SemiBold, FontWeight.SemiBold),
    Font(Res.font.SchibstedGrotesk_Bold, FontWeight.Bold),
)

/**
 * Tabular figures — use on any style rendering counts, ratings or runtimes
 * so numeral columns don't jitter (stat tiles, rating badges, import counts).
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/**
 * The full M3 type scale from the design doc (sizes/line-heights/tracking
 * exact): display 400, headline/title/label SemiBold 600, body 400.
 * Built in composition because [Font] resources are `@Composable` reads.
 */
@Composable
fun muvissTypography(): Typography {
    val family = schibstedGrotesk()
    return Typography(
        displayLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 57.sp, lineHeight = 64.sp, letterSpacing = (-0.25).sp),
        displayMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 45.sp, lineHeight = 52.sp, letterSpacing = 0.sp),
        displaySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp, letterSpacing = 0.sp),
        headlineLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = 0.sp),
        headlineMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.sp),
        headlineSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
        titleLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
        titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.15.sp),
        titleSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.5.sp),
        bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.25.sp),
        bodySmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
        labelLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        labelMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
        labelSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    )
}
