package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb

/** What a rendered pill's central band shows: the text ink, and the backdrops the text sits on. */
internal data class BadgeBand(
    val ink: Color,
    /** The lightest backdrop tone that covers enough of the band: the worst case for the text. */
    val lightest: Color,
    /** The darkest backdrop tone that covers enough of the band. */
    val darkest: Color,
)

/** Exact-colour dominance, as used for the synthetic backdrops (flat fills, so no bucketing needed). */
internal const val SYNTHETIC_DOMINANT_SHARE = 0.05

/**
 * Samples the pill's central band from rendered pixels.
 *
 * The band is the middle of the pill, which lies wholly inside its rounded
 * ends, so no backdrop outside the pill is read by mistake. The ink is the
 * brightest pixel in the band: the glyph ink, the palest thing drawn there.
 *
 * A backdrop is a tone covering at least [dominantShare] of the band, so
 * glyph anti-aliasing (many intermediate colours, a few pixels each) does not
 * count as one. [bucketShift] quantises each channel by that many bits before
 * counting, so a photograph whose pixels never repeat exactly can still show a
 * dominant tone; a bucket's representative is its median-luminance pixel, which
 * is an actual pixel. With `bucketShift = 0` the tones are exact colours.
 *
 * The bucket holding the ink is never a backdrop.
 */
internal fun sampleBadgeBand(
    pixels: PixelMap,
    bucketShift: Int = 0,
    dominantShare: Double = SYNTHETIC_DOMINANT_SHARE,
): BadgeBand {
    val height = pixels.height
    // Stadium-shaped pill: the rounded ends have radius height/2, so this band is inside it.
    val band = (height / 2 until pixels.width - height / 2).flatMap { x ->
        (0 until height).map { y -> pixels[x, y] }
    }
    val ink = band.maxBy { wcagRelativeLuminance(it) }
    val inkBucket = bucketOf(ink, bucketShift)
    val minCount = band.size * dominantShare
    val backdrops = band
        .groupBy { bucketOf(it, bucketShift) }
        .filterKeys { it != inkBucket }
        .values
        .filter { it.size >= minCount }
        .map { bucket -> bucket.sortedBy { wcagRelativeLuminance(it) }[bucket.size / 2] }
    require(backdrops.isNotEmpty()) {
        "no backdrop tone covers ${dominantShare * 100}% of the pill's band, so it cannot be sampled"
    }
    return BadgeBand(
        ink = ink,
        lightest = backdrops.maxBy { wcagRelativeLuminance(it) },
        darkest = backdrops.minBy { wcagRelativeLuminance(it) },
    )
}

private fun bucketOf(color: Color, shift: Int): Int {
    val argb = color.toArgb()
    val r = (argb shr 16 and 0xFF) shr shift
    val g = (argb shr 8 and 0xFF) shr shift
    val b = (argb and 0xFF) shr shift
    return (r shl 16) or (g shl 8) or b
}
