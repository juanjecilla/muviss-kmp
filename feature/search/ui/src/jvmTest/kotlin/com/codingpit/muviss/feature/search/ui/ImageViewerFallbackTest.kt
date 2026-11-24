@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, coil3.annotation.ExperimentalCoilApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import coil3.ColorImage
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.TitleImage
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How the scripted loader answers one address. */
private sealed interface Answer {
    /** Loads at once, as a picture of this size. */
    data class Loads(val size: Size) : Answer

    /** Fails. */
    data object Fails : Answer

    /** Holds the request until [gate] is completed, then loads, so a test can look at the screen while it is pending. */
    class Waits(val gate: CompletableDeferred<Unit>, val size: Size) : Answer
}

/**
 * Answers each address as [answers] says and records every address asked for. [finished] lists the addresses whose
 * request has ended, loaded or failed, so a test can wait for the outcome before it looks.
 */
private class ScriptedFetcherFactory(
    private val answers: Map<String, Answer>,
    val requested: MutableList<String>,
    val finished: MutableList<String>,
) : Fetcher.Factory<Uri> {
    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        val url = data.toString()
        requested += url
        try {
            when (val answer = answers[url] ?: Answer.Fails) {
                is Answer.Loads -> picture(answer.size)

                is Answer.Waits -> {
                    answer.gate.await()
                    picture(answer.size)
                }

                Answer.Fails -> throw IOException("simulated failure")
            }
        } finally {
            finished += url
        }
    }

    private fun picture(size: Size): ImageFetchResult = ImageFetchResult(ColorImage(color = 0xFF000000.toInt(), width = size.width.toInt(), height = size.height.toInt()), false, DataSource.MEMORY)
}

/** A fresh loader for one test, answering as [answers] says. */
private fun installLoader(answers: Map<String, Answer>): ScriptedFetcherFactory {
    val factory = ScriptedFetcherFactory(answers, CopyOnWriteArrayList(), CopyOnWriteArrayList())
    SingletonImageLoader.setUnsafe(ImageLoader.Builder(PlatformContext.INSTANCE).components { add(factory) }.build())
    return factory
}

private fun ComposeUiTest.showing(shown: ShownImage): Boolean = onAllNodesWithTag(imageViewerImageTag(shown)).fetchSemanticsNodes().isNotEmpty()

/**
 * The Image Viewer's first page while the source's larger copy of the tapped image replaces it. Each row is a state the
 * two loads can be in; every row asserts what the page draws and the size of that picture, which Telephoto's bounds are
 * computed from. A row that held the loads in another state would not show the same thing, so the table is the contract.
 *
 * The loads are controlled, never timed: a request is either answered at once, held until the test releases it, or
 * failed, and the test waits for the outcome it needs before it looks at the screen.
 */
class ImageViewerFallbackTest {

    private val tapped = TitleImage("https://image.tmdb.org/t/p/w780/backdrop.jpg")
    private val replacement = TitleImage("https://image.tmdb.org/t/p/original/backdrop.jpg")
    private val other = TitleImage("https://image.tmdb.org/t/p/w1280/other.jpg")
    private val tappedSize = Size(800f, 450f)
    private val replacementSize = Size(1600f, 900f)
    private val otherSize = Size(1280f, 720f)

    /** The size each page last reported, by its address. */
    private val reported = mutableMapOf<String, Size?>()
    private val recordSize: (TitleImage, Size?) -> Unit = { image, size -> reported[image.url] = size }

    private fun ComposeUiTest.viewer(images: List<TitleImage>, seed: TitleImage? = tapped) = setContent {
        MuvissTheme(darkTheme = true) {
            ImageViewerContent(images, ImageKind.Backdrop, onClose = {}, seed = seed, onPageSize = recordSize)
        }
    }

    @Test
    fun the_tapped_image_stays_on_screen_while_a_replacement_is_pending_then_the_replacement_takes_over() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val loader = installLoader(mapOf(tapped.url to Answer.Loads(tappedSize), replacement.url to Answer.Waits(gate, replacementSize)))
        viewer(listOf(replacement))

        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Tapped) }
        assertEquals(tappedSize, reported[replacement.url], "the picture on screen is the tapped image, so the bounds are its size")
        assertTrue(replacement.url in loader.requested, "the replacement is asked for while the tapped image is shown")

        gate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Replacement) }
        assertEquals(replacementSize, reported[replacement.url])
    }

    @Test
    fun a_replacement_that_fails_after_the_tapped_image_loaded_leaves_the_tapped_image_on_screen() = runComposeUiTest {
        val loader = installLoader(mapOf(tapped.url to Answer.Loads(tappedSize), replacement.url to Answer.Fails))
        viewer(listOf(replacement))

        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Tapped) }
        waitUntil(timeoutMillis = 10_000) { replacement.url in loader.finished }
        waitForIdle()

        assertTrue(showing(ShownImage.Tapped), "the tapped image must stay after the replacement failed")
        assertEquals(tappedSize, reported[replacement.url])
    }

    @Test
    fun a_replacement_that_fails_before_the_tapped_image_loads_shows_nothing_until_the_tapped_image_does() = runComposeUiTest {
        val seedGate = CompletableDeferred<Unit>()
        val loader = installLoader(mapOf(tapped.url to Answer.Waits(seedGate, tappedSize), replacement.url to Answer.Fails))
        viewer(listOf(replacement))

        waitUntil(timeoutMillis = 10_000) { replacement.url in loader.finished }
        waitForIdle()
        assertFalse(showing(ShownImage.Tapped), "nothing is drawn yet: the tapped image has not loaded")
        assertTrue(showing(ShownImage.Nothing))
        assertEquals(null, reported[replacement.url], "nothing is drawn, so there is no size to report")

        seedGate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Tapped) }
        assertEquals(tappedSize, reported[replacement.url], "the size must follow the tapped image once it is what is drawn")
    }

    @Test
    fun a_failed_tapped_image_draws_nothing_and_a_replacement_that_loads_still_takes_over() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val loader = installLoader(mapOf(tapped.url to Answer.Fails, replacement.url to Answer.Waits(gate, replacementSize)))
        viewer(listOf(replacement))

        waitUntil(timeoutMillis = 10_000) { tapped.url in loader.finished }
        waitForIdle()
        assertFalse(showing(ShownImage.Tapped))
        assertEquals(null, reported[replacement.url])

        gate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Replacement) }
        assertEquals(replacementSize, reported[replacement.url])
    }

    @Test
    fun a_page_that_comes_back_mid_load_shows_the_tapped_image_again_at_once() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        installLoader(mapOf(tapped.url to Answer.Loads(tappedSize), replacement.url to Answer.Waits(gate, replacementSize), other.url to Answer.Loads(otherSize)))
        viewer(listOf(replacement, other))

        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Tapped) }
        onNodeWithTag(IMAGE_VIEWER_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        waitUntil(timeoutMillis = 10_000) { reported[other.url] == otherSize }

        onNodeWithTag(IMAGE_VIEWER_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Tapped) }
        assertEquals(tappedSize, reported[replacement.url])
        assertFalse(showing(ShownImage.Replacement), "the replacement is still pending, so it is not on screen")
    }

    @Test
    fun a_rotation_remounts_the_viewer_and_it_shows_what_it_showed_before() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        installLoader(mapOf(tapped.url to Answer.Loads(tappedSize), replacement.url to Answer.Waits(gate, replacementSize)))
        var generation by mutableStateOf(0)
        setContent {
            key(generation) {
                MuvissTheme(darkTheme = true) {
                    ImageViewerContent(listOf(replacement), ImageKind.Backdrop, onClose = {}, seed = tapped, onPageSize = recordSize)
                }
            }
        }

        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Tapped) }
        runOnIdle { generation++ }

        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Tapped) }
        assertEquals(tappedSize, reported[replacement.url])
        assertFalse(showing(ShownImage.Replacement))
    }

    @Test
    fun a_page_that_is_not_the_tapped_image_asks_for_no_fallback() = runComposeUiTest {
        val loader = installLoader(mapOf(tapped.url to Answer.Loads(tappedSize), other.url to Answer.Loads(otherSize)))
        viewer(listOf(other))

        waitUntil(timeoutMillis = 10_000) { reported[other.url] == otherSize }
        waitForIdle()

        assertTrue(showing(ShownImage.Replacement))
        assertFalse(tapped.url in loader.requested, "a different image must not pull the tapped one in: ${loader.requested}")
    }

    @Test
    fun a_replacement_that_succeeds_takes_over_even_when_the_tapped_image_failed() = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val loader = installLoader(mapOf(tapped.url to Answer.Fails, replacement.url to Answer.Waits(gate, replacementSize)))
        viewer(listOf(replacement))

        waitUntil(timeoutMillis = 10_000) { tapped.url in loader.finished }
        gate.complete(Unit)
        waitUntil(timeoutMillis = 10_000) { showing(ShownImage.Replacement) }
        assertEquals(replacementSize, reported[replacement.url])
    }
}
