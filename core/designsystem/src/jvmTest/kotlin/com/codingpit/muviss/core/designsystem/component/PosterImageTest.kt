@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, coil3.annotation.ExperimentalCoilApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.fetch.Fetcher
import coil3.request.Options
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** A loader whose every fetch fails, counting the URLs it was asked for. */
private class FailingFetcherFactory(val requested: MutableList<String>) : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        requested += data.toString()
        throw IOException("simulated failure")
    }
}

class PosterImageTest {

    @Test
    fun without_a_url_the_title_is_shown() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { PosterImage(url = null, title = "Dune") } }

        onNodeWithText("Dune").assertExists()
    }

    @Test
    fun a_blank_url_is_treated_as_no_url() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { PosterImage(url = "  ", title = "Dune") } }

        onNodeWithText("Dune").assertExists()
    }

    @Test
    fun a_failed_load_falls_back_to_the_title_and_asks_for_the_size_class_width() = runComposeUiTest {
        val requested = mutableListOf<String>()
        SingletonImageLoader.setUnsafe(
            ImageLoader.Builder(PlatformContext.INSTANCE).components { add(FailingFetcherFactory(requested)) }.build(),
        )
        setContent {
            MuvissTheme(darkTheme = true) {
                PosterImage(url = "https://image.tmdb.org/t/p/w500/x.jpg", title = "Dune", size = PosterSize.Thumbnail)
            }
        }

        waitUntil(timeoutMillis = 10_000) { requested.isNotEmpty() }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("Dune").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(listOf("https://image.tmdb.org/t/p/w185/x.jpg"), requested.distinct())
    }
}
