@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.testing

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.fail

/**
 * Golden-image assertions, hand-rolled over Skiko.
 *
 * The repo takes no new UI dependencies, and the multiplatform
 * `runComposeUiTest` path already works, so this is ~a screen of code rather
 * than Roborazzi or Paparazzi. What those buy that this does not is a
 * font-locked renderer — which the app already provides for free, because
 * `MuvissTheme` sets Schibsted Grotesk (a bundled `composeResources` font) on
 * every text style, so no host font ever participates in a golden.
 *
 * Comparison is deliberately **not** byte equality. Skia is the same engine on
 * macOS and on `ubuntu-latest`, but text is not rasterised identically across
 * them, so a tolerance absorbs the residual drift. It is tight enough that a
 * real layout shift — a card moving 10dp — moves far more than
 * [TOLERATED_FRACTION] of the pixels.
 *
 * How much drift there is depends on how much *text* a golden contains, which
 * is why [assertMatchesGolden] takes a `tolerance`. Measured between a macOS
 * recording and the same frame rendered on `ubuntu-latest`: the triage deck and
 * the inset samples stay under the 0.5% default, while the search screen — wall
 * to wall labels — moves 2.15%, and its diff image marks the glyphs and nothing
 * else. Linux draws the same font perceptibly heavier rather than merely
 * blurrier, so this is not edge noise that a blur or a downsample can average
 * away (both were tried against the real CI capture; downsampling made the
 * measured difference *worse*, 3.1% at 2x). Widening the per-pixel channel
 * delta would hide it, at the cost of no longer noticing a colour token
 * changing. Widening the area instead keeps every pixel judged strictly and
 * only says how much of the frame is allowed to be text.
 */
fun ComposeUiTest.assertMatchesGolden(name: String, tolerance: Double = TOLERATED_FRACTION) {
    waitForIdle()
    // The [GoldenSurface] frame when there is one, so the image is the same
    // size on every machine; the whole root otherwise.
    val surfaces = onAllNodesWithTag(GOLDEN_SURFACE_TAG).fetchSemanticsNodes()
    if (surfaces.isEmpty()) {
        onRoot().assertMatchesGolden(name, tolerance)
    } else {
        onNodeWithTag(GOLDEN_SURFACE_TAG).assertMatchesGolden(name, tolerance)
    }
}

/** As [assertMatchesGolden], but captures one node rather than the whole root. */
fun SemanticsNodeInteraction.assertMatchesGolden(name: String, tolerance: Double = TOLERATED_FRACTION) {
    val actual = captureToImage().toBufferedImage()
    val golden = goldenFile(name)

    if (recording) {
        golden.parentFile.mkdirs()
        ImageIO.write(actual, "png", golden)
        // Loud on purpose: a recorded golden asserts nothing, and a test run
        // left in record mode would go green forever.
        println("RECORDED golden ${golden.path} (${actual.width}x${actual.height})")
        return
    }

    if (!golden.exists()) {
        fail(
            "No golden for '$name'. Expected ${golden.path}.\n" +
                "Record it with:  ./gradlew <module>:jvmTest -Precord",
        )
    }

    val expected = ImageIO.read(golden)
        ?: fail("Golden ${golden.path} is not a readable image.")

    if (expected.width != actual.width || expected.height != actual.height) {
        writeFailureArtifacts(name, actual, diff = null)
        fail(
            "Golden '$name' size changed: expected ${expected.width}x${expected.height}, " +
                "got ${actual.width}x${actual.height}. See ${failureDir(name).path}",
        )
    }

    val failure = goldenMismatch(expected, actual, tolerance, name)
    if (failure != null) {
        writeFailureArtifacts(name, actual, compare(expected, actual).second)
        fail(
            failure + "\nActual and diff written to ${failureDir(name).path}\n" +
                "If the change is intended, re-record with:  ./gradlew <module>:jvmTest -Precord",
        )
    }
}

/**
 * Why [actual] does not match [expected] at [tolerance], or null when it does:
 * the whole judgement of [assertMatchesGolden] without the files, so the
 * harness itself can be tested (#277).
 *
 * Two checks, because the area [tolerance] alone cannot see a colour change on
 * text: text is exactly the share of the frame that tolerance exists to ignore.
 * #277 measured it — GoldenSurface's text going from black to `onBackground`
 * moved 0.1-1.7% of every affected golden, and every one of them passed at
 * 3.5%. So the palette is checked first ([missingColours]).
 */
internal fun goldenMismatch(expected: BufferedImage, actual: BufferedImage, tolerance: Double = TOLERATED_FRACTION, name: String = "image"): String? {
    val pixels = actual.width * actual.height
    val fraction = compare(expected, actual).first.toDouble() / pixels
    val lost = missingColours(expected, actual)
    val gained = missingColours(actual, expected)
    // Every golden, unchanged ones included: the report is how a tolerance or
    // the palette thresholds get chosen, and an identical render is a data point.
    if (System.getProperty("muviss.golden.report") == "true") {
        val kept = minOf(worstKeptShare(expected, actual), worstKeptShare(actual, expected))
        println("GOLDEN-REPORT %s moved=%.4f%% worstKept=%.3f lost=%s gained=%s".format(name, fraction * 100, kept, lost, gained))
    }
    if (lost.isNotEmpty() || gained.isNotEmpty()) {
        return "Golden '$name' changed colour: " +
            (if (lost.isNotEmpty()) "no longer drawn ${lost.joinToString()}; " else "") +
            (if (gained.isNotEmpty()) "newly drawn ${gained.joinToString()}; " else "") +
            "whatever the area tolerance (%.3f%% moved).".format(fraction * 100)
    }
    if (fraction > tolerance) {
        return "Golden '$name' differs: %.3f%% of pixels moved by more than $TOLERATED_CHANNEL_DELTA/255 "
            .format(fraction * 100) +
            "(tolerance %.3f%%).".format(tolerance * 100)
    }
    return null
}

/**
 * The colours [from] draws on at least [PALETTE_MIN_PIXELS] pixels that [to]
 * has all but lost: fewer than [PALETTE_KEPT_SHARE] as many pixels within
 * [TOLERATED_CHANNEL_DELTA] of them. Formatted `#RRGGBB (n px)`.
 *
 * Why this tells a recolour from Linux drift (#277): drawing a glyph heavier
 * changes how many pixels each anti-aliasing tone covers, but the ink and the
 * background are still the same colours, so every common colour keeps a
 * counterpart. A colour token change replaces one: the old ink is gone.
 */
private fun missingColours(from: BufferedImage, to: BufferedImage): List<String> {
    val source = histogram(from)
    val target = histogram(to)
    return source.filter { (_, count) -> count >= PALETTE_MIN_PIXELS }
        .mapNotNull { (colour, count) ->
            val near = target.entries.sumOf { (other, n) -> if (movedTooFar(colour, other)) 0 else n }
            if (near < count * PALETTE_KEPT_SHARE) "#%06X (%d px)".format(colour and 0xFFFFFF, count) else null
        }
        .take(MAX_REPORTED_COLOURS)
}

/** For the report only: the smallest share of a palette colour's pixels that kept a counterpart. */
private fun worstKeptShare(from: BufferedImage, to: BufferedImage): Double {
    val target = histogram(to)
    return histogram(from).filter { it.value >= PALETTE_MIN_PIXELS }
        .minOfOrNull { (colour, count) -> target.entries.sumOf { (other, n) -> if (movedTooFar(colour, other)) 0 else n }.toDouble() / count }
        ?: 1.0
}

private fun histogram(image: BufferedImage): Map<Int, Int> {
    val counts = HashMap<Int, Int>()
    for (y in 0 until image.height) for (x in 0 until image.width) counts.merge(image.getRGB(x, y), 1, Int::plus)
    return counts
}

/**
 * Counts pixels that moved further than [TOLERATED_CHANNEL_DELTA] on any
 * channel, and paints a diff image marking them so a failure is readable
 * without an image-diff tool.
 */
private fun compare(expected: BufferedImage, actual: BufferedImage): Pair<Int, BufferedImage> {
    val diff = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB)
    var differing = 0
    for (y in 0 until actual.height) {
        for (x in 0 until actual.width) {
            val e = expected.getRGB(x, y)
            val a = actual.getRGB(x, y)
            if (movedTooFar(e, a)) {
                differing++
                diff.setRGB(x, y, DIFF_MARK)
            } else {
                // Ghost the unchanged content so the marks have context.
                diff.setRGB(x, y, (a and 0x00FFFFFF) or GHOST_ALPHA)
            }
        }
    }
    return differing to diff
}

private fun movedTooFar(expected: Int, actual: Int): Boolean {
    for (shift in intArrayOf(24, 16, 8, 0)) {
        val e = (expected shr shift) and 0xFF
        val a = (actual shr shift) and 0xFF
        if (abs(e - a) > TOLERATED_CHANNEL_DELTA) return true
    }
    return false
}

private fun ImageBitmap.toBufferedImage(): BufferedImage {
    val pixels = toPixelMap()
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
        for (x in 0 until width) {
            val color = pixels[x, y]
            image.setRGB(
                x,
                y,
                (channel(color.alpha) shl 24) or
                    (channel(color.red) shl 16) or
                    (channel(color.green) shl 8) or
                    channel(color.blue),
            )
        }
    }
    return image
}

private fun channel(value: Float): Int = (value * 255f + 0.5f).toInt().coerceIn(0, 255)

private fun writeFailureArtifacts(name: String, actual: BufferedImage, diff: BufferedImage?) {
    val dir = failureDir(name).also { it.mkdirs() }
    ImageIO.write(actual, "png", File(dir, "$name.actual.png"))
    diff?.let { ImageIO.write(it, "png", File(dir, "$name.diff.png")) }
}

/**
 * Goldens live beside the tests that record them, not on the classpath —
 * recording has to be able to *write* the file, and a classpath URL points at
 * the build output, which the next `clean` throws away.
 *
 * `muviss.golden.dir` is set per-module by the `muviss.kmp.compose` convention.
 * The fallback keeps a bare `jvmTest` run from a module directory working.
 */
private fun goldenFile(name: String): File = File(goldenDir, "$name.png")

private fun failureDir(name: String): File = File(System.getProperty("java.io.tmpdir"), "muviss-goldens/$name")

private val goldenDir: File
    get() = System.getProperty("muviss.golden.dir")
        ?.let(::File)
        ?: File(System.getProperty("user.dir"), "src/jvmTest/resources/screenshots")

private val recording: Boolean
    get() = System.getProperty("muviss.golden.record") == "true" ||
        System.getenv("MUVISS_RECORD_GOLDENS") == "1"

/** Fail once more than this fraction of pixels has moved, unless a caller says otherwise. */
const val TOLERATED_FRACTION = 0.005

/**
 * A colour counts as part of the palette once it covers this many pixels: the
 * full-coverage ink of a few words, never a lone anti-aliasing tone.
 *
 * Measured 2026-10-17 (#277) by rendering all 92 macOS-recorded goldens on
 * Linux (FreeType, Ubuntu 24.04 container, `-PgoldenReport`): area drift up to
 * 3.17% (persistence banner), and **no** palette colour lost or gained on any
 * of them — the worst kept 68% of its pixels' counterparts (settings screen)
 * against the 25% [PALETTE_KEPT_SHARE] floor. The black → `onBackground` text
 * change #277 found keeps 0%.
 */
const val PALETTE_MIN_PIXELS = 24

/** A palette colour is lost when the other image keeps less than this share of its pixels (within the channel delta). */
const val PALETTE_KEPT_SHARE = 0.25

private const val MAX_REPORTED_COLOURS = 5

/** How far one channel may drift before a pixel counts as changed. */
private const val TOLERATED_CHANNEL_DELTA = 8

private const val DIFF_MARK = 0xFFFF00FF.toInt()
private const val GHOST_ALPHA = 0x30000000
