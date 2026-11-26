@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, coil3.annotation.ExperimentalCoilApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.component.PosterImage
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.ImageKind
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Artwork that is still loading is named by its kind alone. [PosterImage] names its picture by the title, and the
 * opener's name must not be merged with it: a screen reader would otherwise read "Still, <episode title>" while the
 * picture loads and "Still" once it fails.
 */
class OpensImageViewerNameTest {

    private val url = "https://image.tmdb.org/t/p/w500/artwork.jpg"

    @Test
    fun a_poster_still_loading_is_named_poster_alone() = loadingIsNamedByKind(ImageKind.Poster, "Poster")

    @Test
    fun a_backdrop_still_loading_is_named_backdrop_alone() = loadingIsNamedByKind(ImageKind.Backdrop, "Backdrop")

    @Test
    fun a_still_still_loading_is_named_still_alone() = loadingIsNamedByKind(ImageKind.Still, "Still")

    private fun loadingIsNamedByKind(kind: ImageKind, name: String) = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        val loader = installLoader(mapOf(url to Answer.Waits(gate, Size(300f, 450f))))
        try {
            setContent {
                MuvissTheme(darkTheme = true) {
                    PosterImage(
                        url = url,
                        title = "Episode Title",
                        modifier = Modifier.size(200.dp).opensImageViewer(url, kind) { _, _ -> },
                    )
                }
            }
            waitUntil(timeoutMillis = 10_000) { url in loader.requested }
            waitForIdle()

            val node = onNodeWithContentDescription(name).fetchSemanticsNode()
            assertEquals(listOf(name), node.config[SemanticsProperties.ContentDescription])
        } finally {
            gate.complete(Unit)
        }
    }
}
