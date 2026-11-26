package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks the photograph sampling rule of [sampleBadgeBand] (bucketShift 3, 2% share) against a synthetic
 * band with known backdrop tones and glyph anti-aliasing (#627). No picture is involved: the band is built
 * pixel by pixel, so every backdrop tone is known and the rule's error can be read off.
 *
 * The band stands in for a photograph under the badge: a horizontal gradient (40 to 120 levels) plus
 * per-pixel grain, with anti-aliased glyph pixels (ink mixed into the backdrop at a coverage of 0.2 to 0.8)
 * scattered over it. A "patch" is a light highlight of one tone with the same grain, covering a set fraction
 * of the band; it is what the rule has to count.
 *
 * Two errors matter, and they point opposite ways:
 * - a glyph edge counted as backdrop makes the sampled tone lighter than the true one (the text looks worse
 *   than it is: conservative);
 * - a light patch the rule does not count makes the sampled tone darker than the true one (the text looks
 *   better than it is: not conservative, and the one that can hide a failure).
 *
 * Tones are gray levels 0..255; a sampled tone is compared by its red channel.
 *
 * What the grid shows at the shipped rule (bucketShift 3, 2%), recorded in docs/ACCESSIBILITY.md:
 * - no patch: the sampled tone reads up to 11 levels under the true lightest, because a bucket is represented by
 *   its median pixel, not its brightest;
 * - a patch of 1% to 2% of the band is always missed, and a 3% patch is missed at grain 4 and 8: the glyph pixels
 *   inside it and the bucket boundaries (8 levels wide) split it below the 2% share. A 5% patch is found at every
 *   grain tested;
 * - no sampling error can fail the AA claim itself: behind the 70% scrim no backdrop composites lighter than
 *   #4C4C4C, which is 5.6:1 against the ink, so the true lightest ratio is never below 5.6:1. The rule can only
 *   misstate a poster's figure, not hide a failure.
 * The rule is kept, and a sliding window replacing the fixed buckets is tracked as a follow-up.
 */
class RatingBadgeSamplingRuleTest {

    @Test
    fun the_sampling_grid_is_printed_for_the_record() {
        println(table())
    }

    @Test
    fun a_grainy_backdrop_without_a_patch_is_read_within_two_buckets_of_its_lightest_tone() {
        for (grain in GRAIN_TABLE) {
            val band = SyntheticBand(grain = grain, patchFraction = 0.0)
            val sampled = sampleBadgeBand(band.pixels, bucketShift = PHOTO_SHIFT, dominantShare = PHOTO_SHARE)
            val under = band.trueLightestLevel - level(sampled.lightest)
            assertTrue(
                under <= MAX_UNDER_READ_LEVELS,
                "grain $grain: sampled tone ${level(sampled.lightest)} is $under levels under the true " +
                    "lightest ${band.trueLightestLevel}, more than $MAX_UNDER_READ_LEVELS",
            )
        }
    }

    @Test
    fun a_five_percent_patch_is_counted_at_every_grain_the_table_covers() {
        for (grain in GRAIN_TABLE) {
            val band = SyntheticBand(grain = grain, patchFraction = 0.05)
            val sampled = sampleBadgeBand(band.pixels, bucketShift = PHOTO_SHIFT, dominantShare = PHOTO_SHARE)
            assertTrue(
                level(sampled.lightest) >= PATCH_FOUND_LEVEL,
                "grain $grain: a 5% patch at $PATCH_TONE was not counted; sampled ${level(sampled.lightest)}",
            )
        }
    }

    private fun table(): String = buildString {
        appendLine("SAMPLING RULE, synthetic band (gradient 40..120 + grain, glyph AA ${GLYPH_SHARE * 100}%, patch tone $PATCH_TONE)")
        appendLine("miss = a patch covering the area was not counted (sampled tone below $PATCH_FOUND_LEVEL)")
        appendLine("%-6s %-7s %-6s %-7s %-10s %-10s %-6s".format("shift", "share", "grain", "patch", "sampled", "true", "note"))
        for (shift in listOf(0, 2, 3, 4)) {
            for (share in listOf(0.02, 0.05)) {
                for (grain in GRAIN_TABLE) {
                    for (patch in PATCH_TABLE) {
                        val band = SyntheticBand(grain = grain, patchFraction = patch)
                        val sampled = runCatching { sampleBadgeBand(band.pixels, bucketShift = shift, dominantShare = share) }
                        val head = "%-6d %-7s %-6d %-7s ".format(shift, "%.0f%%".format(share * 100), grain, "%.0f%%".format(patch * 100))
                        val row = sampled.fold(
                            onSuccess = { band0 ->
                                val sampledLevel = level(band0.lightest)
                                val miss = patch > 0 && sampledLevel < PATCH_FOUND_LEVEL
                                "%-10d %-10d %-6s".format(sampledLevel, band.trueLightestLevel, if (miss) "miss" else "")
                            },
                            onFailure = { "%-10s %-10d %-6s".format("no tone", band.trueLightestLevel, "") },
                        )
                        appendLine(head + row)
                    }
                }
            }
        }
    }

    private fun level(c: Color): Int = Math.round(c.red * 255f)

    /**
     * The brightest level l such that at least [minCount] non-glyph cells lie in [l - BUCKET_LEVELS + 1, l]. This is
     * the tone a perfect reader of "a tone covering the share" would report; a single bright outlier pixel does not
     * count, because text does not sit on one pixel.
     */
    private fun lightestCovering(backdrop: IntArray, glyph: BooleanArray, minCount: Double): Int {
        val histogram = IntArray(256)
        for (i in backdrop.indices) if (!glyph[i] && isInBand(i)) histogram[backdrop[i]]++
        for (l in 255 downTo 0) {
            var count = 0
            for (v in maxOf(0, l - BUCKET_LEVELS + 1)..l) count += histogram[v]
            if (count >= minCount) return l
        }
        return 0
    }

    private fun isInBand(i: Int): Boolean {
        val x = i % BAND_WIDTH
        return x >= BAND_HEIGHT / 2 && x < BAND_WIDTH - BAND_HEIGHT / 2
    }

    private companion object {
        const val PHOTO_SHIFT = 3
        const val PHOTO_SHARE = 0.02
        const val PATCH_TONE = 200

        /** A sampled tone at or above this counts the patch: it is the patch, not the gradient (max 120 + grain). */
        const val PATCH_FOUND_LEVEL = 180
        const val BUCKET_LEVELS = 8

        /**
         * Measured worst under-read at the shipped rule (shift 3, 2%) over the grains and patches above: 11 levels,
         * from the bucket's median pixel being used as its representative. 16 is two buckets, the bound asserted.
         */
        const val MAX_UNDER_READ_LEVELS = 2 * BUCKET_LEVELS
        const val GLYPH_SHARE = 0.15
        const val BAND_WIDTH = 60
        const val BAND_HEIGHT = 20
        const val BAND_CELLS = (BAND_WIDTH - BAND_HEIGHT) * BAND_HEIGHT
        const val SEED = 627
        val GRAIN_TABLE = listOf(0, 4, 8)
        val PATCH_TABLE = listOf(0.0, 0.01, 0.02, 0.03, 0.05)
        val INK: Color = Color(0xFFFFCB6B)
    }

    /**
     * The pill's band as a [PixelMap]. The sampler reads columns `height/2 until width - height/2`; the ends are
     * outside it, so they are left black. Ink is mixed into glyph pixels. [trueLightestLevel] is the lightest
     * backdrop level outside the glyphs, which is what a perfect sampler would report.
     */
    private inner class SyntheticBand(grain: Int, patchFraction: Double) {
        val pixels: PixelMap

        /** The lightest tone that covers [PHOTO_SHARE] of the band, the value the sampler should report. */
        val trueLightestLevel: Int

        init {
            val random = Random(SEED)
            val inset = BAND_HEIGHT / 2
            val bandColumns = BAND_WIDTH - 2 * inset
            val cells = BAND_WIDTH * BAND_HEIGHT
            val backdrop = IntArray(cells)
            val glyph = BooleanArray(cells)
            val coverage = DoubleArray(cells)
            // The patch is whole columns from the band's right end, so it is one contiguous area.
            val patchCells = (bandColumns * BAND_HEIGHT * patchFraction).toInt()
            var patchLeft = patchCells
            for (x in BAND_WIDTH - inset - 1 downTo inset) {
                for (y in 0 until BAND_HEIGHT) {
                    val i = y * BAND_WIDTH + x
                    val inPatch = patchLeft > 0
                    if (inPatch) patchLeft--
                    val base = if (inPatch) PATCH_TONE else 40 + (80 * (x - inset)) / (bandColumns - 1)
                    backdrop[i] = (base + random.nextInt(-grain, grain + 1)).coerceIn(0, 255)
                    if (random.nextDouble() < GLYPH_SHARE) {
                        glyph[i] = true
                        coverage[i] = 0.2 + 0.6 * random.nextDouble()
                    }
                }
            }
            trueLightestLevel = lightestCovering(backdrop, glyph, minCount = BAND_CELLS * PHOTO_SHARE)
            val argb = IntArray(cells)
            for (i in 0 until cells) {
                val a = if (glyph[i]) coverage[i].toFloat() else 0f
                val bg = backdrop[i] / 255f
                argb[i] = Color(
                    red = bg * (1 - a) + INK.red * a,
                    green = bg * (1 - a) + INK.green * a,
                    blue = bg * (1 - a) + INK.blue * a,
                ).toArgb()
            }
            // Columns outside the sampler's band stay black, as the rounded ends of a pill do.
            for (y in 0 until BAND_HEIGHT) {
                for (x in 0 until inset) {
                    argb[y * BAND_WIDTH + x] = Color.Black.toArgb()
                    argb[y * BAND_WIDTH + BAND_WIDTH - 1 - x] = Color.Black.toArgb()
                }
            }
            pixels = PixelMap(argb, width = BAND_WIDTH, height = BAND_HEIGHT, bufferOffset = 0, stride = BAND_WIDTH)
        }
    }
}
