@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Image as SkiaImage

/**
 * [RatingBadgeRenderedContrastTest] over real poster artwork, read from a folder
 * on this machine. Posters cannot be committed (licence and size), so this test
 * is opt-in and skipped in every normal run.
 *
 * Run it with `MUVISS_POSTER_DIR=/absolute/path/to/posters`. The folder holds
 * image files (jpg, jpeg, png, webp). Each one is drawn as a poster-sized
 * backdrop with the badge at the card's `TopEnd` corner, the same placement
 * as `PosterCard`, then sampled the way the synthetic test samples it. Fetch
 * steps and the recorded numbers are in docs/ACCESSIBILITY.md.
 *
 * Every fixture's lightest composited backdrop must reach 4.5:1 against the
 * ink. The darkest backdrop is reported, not asserted: it is the easy case.
 * An empty folder or a missing one fails the run. It does not pass vacuously.
 */
class RatingBadgePosterFixturesTest {

    @Test
    fun the_badge_over_every_local_poster_meets_aa_for_text() {
        val folder = System.getenv(POSTER_DIR_ENV)
        assumeTrue("set $POSTER_DIR_ENV to a folder of poster images to run", !folder.isNullOrBlank())
        val fixtures = posterFixtures(File(folder!!))
        assertTrue(fixtures.isNotEmpty(), "$POSTER_DIR_ENV=$folder holds no ${IMAGE_EXTENSIONS.joinToString("/")} files")

        val rows = fixtures.map { measure(it) }
        val table = renderTable(rows)
        println(table)

        val failing = rows.filter { !it.inkIsAmber || it.lightestRatio < AA_TEXT }
        assertTrue(
            failing.isEmpty(),
            "badge text is below ${AA_TEXT}:1 over ${failing.size} of ${rows.size} posters:\n$table",
        )
    }

    private fun posterFixtures(folder: File): List<File> {
        assertTrue(folder.isDirectory, "$POSTER_DIR_ENV=${folder.path} is not a directory")
        return folder.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name }
    }

    private fun measure(file: File): PosterRow {
        val artwork = decode(file)
        var band: BadgeBand? = null
        runComposeUiTest {
            setContent { BadgeOverArtwork(artwork) }
            waitForIdle()
            val pixels: PixelMap = onNodeWithTag(RATING_BADGE_TAG).captureToImage().toPixelMap()
            band = sampleBadgeBand(pixels, bucketShift = PHOTO_BUCKET_SHIFT, dominantShare = PHOTO_DOMINANT_SHARE)
        }
        val sampled = checkNotNull(band)
        return PosterRow(
            name = file.name,
            ink = sampled.ink,
            lightest = sampled.lightest,
            darkest = sampled.darkest,
            lightestRatio = wcagContrastRatio(sampled.ink, sampled.lightest),
            darkestRatio = wcagContrastRatio(sampled.ink, sampled.darkest),
            inkIsAmber = channelsWithin(sampled.ink, RatingBadgeAmber, tolerance = 2),
        )
    }

    private fun decode(file: File): ImageBitmap {
        val image = try {
            SkiaImage.makeFromEncoded(file.readBytes())
        } catch (e: Exception) {
            throw AssertionError("could not decode ${file.name}: ${e.javaClass.simpleName}", e)
        }
        return image.toComposeImageBitmap()
    }

    private fun renderTable(rows: List<PosterRow>): String = buildString {
        appendLine("RATING-BADGE-POSTERS (text ink vs composited backdrop, WCAG; AA text = ${AA_TEXT}:1)")
        appendLine("%-40s %-9s %-9s %-9s %-14s %-14s".format("fixture", "ink", "lightest", "darkest", "lightest:1", "darkest:1"))
        rows.forEach { r ->
            appendLine(
                "%-40s %-9s %-9s %-9s %-14s %-14s".format(
                    r.name.take(40),
                    r.ink.hex(),
                    r.lightest.hex(),
                    r.darkest.hex(),
                    "%.2f%s".format(r.lightestRatio, if (r.lightestRatio < AA_TEXT) " FAIL" else ""),
                    "%.2f".format(r.darkestRatio),
                ),
            )
        }
        if (rows.any { !it.inkIsAmber }) appendLine("note: some fixtures sampled a non-amber ink; the sampler is suspect for them")
    }

    private data class PosterRow(
        val name: String,
        val ink: Color,
        val lightest: Color,
        val darkest: Color,
        val lightestRatio: Double,
        val darkestRatio: Double,
        val inkIsAmber: Boolean,
    )

    private fun channelsWithin(a: Color, b: Color, tolerance: Int): Boolean {
        fun ch(c: Color) = listOf(c.red, c.green, c.blue).map { (it * 255f + 0.5f).toInt() }
        return ch(a).zip(ch(b)).all { (x, y) -> abs(x - y) <= tolerance }
    }

    private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

    private companion object {
        const val POSTER_DIR_ENV = "MUVISS_POSTER_DIR"
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        const val AA_TEXT = 4.5

        /**
         * Photographs do not repeat exact colours, so each channel is quantised to
         * 32 levels and a tone must cover 2% of the band. Anti-aliasing spreads
         * over many buckets and rarely clears that bar; see [sampleBadgeBand].
         */
        const val PHOTO_BUCKET_SHIFT = 3
        const val PHOTO_DOMINANT_SHARE = 0.02
    }
}

/** A 2:3 poster frame; the badge sits at the card's TopEnd corner, as in PosterCard. */
private val POSTER_FRAME_WIDTH = 160.dp
private val POSTER_FRAME_HEIGHT = 240.dp

@Composable
private fun BadgeOverArtwork(artwork: ImageBitmap) {
    MuvissTheme(darkTheme = false) {
        GoldenSurface(width = POSTER_FRAME_WIDTH, height = POSTER_FRAME_HEIGHT) {
            Box(Modifier.fillMaxSize()) {
                Image(
                    painter = BitmapPainter(artwork),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                RatingBadge(
                    rating = SAMPLE_RATING,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
    }
}
