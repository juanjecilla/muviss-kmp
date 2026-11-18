@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.ImageKind
import com.codingpit.muviss.models.TitleImage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Image Viewer's controls. Images never load in a test (there is no network), so what is
 * checked is the paging and closing around them, not the pictures. The gestures — pinch,
 * double-tap, flick — belong to telephoto and are only observable on a device.
 */
class ImageViewerTest {

    private val three = listOf("a.jpg", "b.jpg", "c.jpg").map { TitleImage("https://image.tmdb.org/t/p/w1280/$it") }

    @Test
    fun it_opens_on_the_first_image_and_says_where_it_is() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { ImageViewerContent(three, ImageKind.Backdrop, onClose = {}) } }

        onNodeWithTag(IMAGE_VIEWER_POSITION_TAG).assertTextEquals("1 / 3")
    }

    @Test
    fun the_arrow_keys_page_through_the_gallery() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { ImageViewerContent(three, ImageKind.Backdrop, onClose = {}) } }

        onNodeWithTag(IMAGE_VIEWER_TAG).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithTag(IMAGE_VIEWER_POSITION_TAG).assertTextEquals("2 / 3")

        onNodeWithTag(IMAGE_VIEWER_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithTag(IMAGE_VIEWER_POSITION_TAG).assertTextEquals("1 / 3")
    }

    @Test
    fun the_arrow_keys_stop_at_the_ends() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { ImageViewerContent(three, ImageKind.Backdrop, onClose = {}) } }

        onNodeWithTag(IMAGE_VIEWER_TAG).performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithTag(IMAGE_VIEWER_POSITION_TAG).assertTextEquals("1 / 3")
    }

    @Test
    fun escape_closes_it() = runComposeUiTest {
        var closed = 0
        setContent { MuvissTheme(darkTheme = true) { ImageViewerContent(three, ImageKind.Poster, onClose = { closed++ }) } }

        onNodeWithTag(IMAGE_VIEWER_TAG).performKeyInput { pressKey(Key.Escape) }

        assertEquals(1, closed)
    }

    @Test
    fun the_close_button_closes_it() = runComposeUiTest {
        var closed = 0
        setContent { MuvissTheme(darkTheme = true) { ImageViewerContent(three, ImageKind.Still, onClose = { closed++ }) } }

        onNodeWithTag(IMAGE_VIEWER_CLOSE_TAG).performClick()

        assertEquals(1, closed)
    }

    @Test
    fun a_lone_image_shows_no_position() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { ImageViewerContent(three.take(1), ImageKind.Backdrop, onClose = {}) } }

        assertEquals(0, onAllNodesWithTag(IMAGE_VIEWER_POSITION_TAG).fetchSemanticsNodes().size)
    }
}
