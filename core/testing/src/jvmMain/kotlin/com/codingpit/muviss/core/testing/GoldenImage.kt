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

    val (differing, diff) = compare(expected, actual)
    val fraction = differing.toDouble() / (actual.width * actual.height)
    if (fraction > tolerance) {
        writeFailureArtifacts(name, actual, diff)
        fail(
            "Golden '$name' differs: %.3f%% of pixels moved by more than $TOLERATED_CHANNEL_DELTA/255 "
                .format(fraction * 100) +
                "(tolerance %.3f%%).\n".format(tolerance * 100) +
                "Actual and diff written to ${failureDir(name).path}\n" +
                "If the change is intended, re-record with:  ./gradlew <module>:jvmTest -Precord",
        )
    }
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

/** How far one channel may drift before a pixel counts as changed. */
private const val TOLERATED_CHANNEL_DELTA = 8

private const val DIFF_MARK = 0xFFFF00FF.toInt()
private const val GHOST_ALPHA = 0x30000000
