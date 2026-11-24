@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, coil3.annotation.ExperimentalCoilApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.fetch.Fetcher
import coil3.request.Options
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.TitleImage
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertTrue

/** A loader whose every fetch fails, recording the addresses it was asked for, in order. */
private class RecordingFailingFetcherFactory(val requested: MutableList<String>) : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        requested += data.toString()
        throw IOException("simulated failure")
    }
}

/**
 * The viewer's first page is the tapped image, and the source's larger copy can replace it. When that copy cannot load
 * the tapped image must still be asked for, so it can stay on screen.
 */
class ImageViewerFallbackTest {

    private val tapped = TitleImage("https://image.tmdb.org/t/p/w780/backdrop.jpg")
    private val replacement = TitleImage("https://image.tmdb.org/t/p/original/backdrop.jpg")

    @Test
    fun a_replacement_that_fails_to_load_falls_back_to_the_tapped_image() = runComposeUiTest {
        val requested = mutableListOf<String>()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(PlatformContext.INSTANCE).components { add(RecordingFailingFetcherFactory(requested)) }.build(),
        )
        setContent {
            MuvissTheme(darkTheme = true) {
                ImageViewerContent(listOf(replacement), ImageKind.Backdrop, onClose = {}, seed = tapped)
            }
        }

        waitUntil(timeoutMillis = 10_000) { tapped.url in requested && replacement.url in requested }

        assertTrue(tapped.url in requested, "the tapped image should be asked for while the replacement is unavailable: $requested")
        assertTrue(replacement.url in requested, "the replacement should be attempted: $requested")
    }

    @Test
    fun a_page_that_is_not_the_tapped_image_asks_for_no_fallback() = runComposeUiTest {
        val requested = mutableListOf<String>()
        val other = TitleImage("https://image.tmdb.org/t/p/w1280/other.jpg")
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(PlatformContext.INSTANCE).components { add(RecordingFailingFetcherFactory(requested)) }.build(),
        )
        setContent {
            MuvissTheme(darkTheme = true) {
                ImageViewerContent(listOf(other), ImageKind.Backdrop, onClose = {}, seed = tapped)
            }
        }

        waitUntil(timeoutMillis = 10_000) { other.url in requested }

        assertTrue(tapped.url !in requested, "a different image must not pull the tapped one in: $requested")
    }
}
