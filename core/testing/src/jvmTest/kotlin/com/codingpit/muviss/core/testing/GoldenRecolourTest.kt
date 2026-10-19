package com.codingpit.muviss.core.testing

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.sp
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #277: a text-only colour change used to pass every text-heavy golden, because
 * it moves fewer pixels than the area tolerance those goldens need for Linux's
 * heavier glyphs. These render the same wall of text twice and judge it the way
 * [assertMatchesGolden] does.
 */
@OptIn(ExperimentalTestApi::class)
class GoldenRecolourTest {

    /** The tolerance the text-heavy goldens (profile, banner) run at. */
    private val textHeavy = 0.035

    private fun render(textColor: Color): BufferedImage {
        var image: BufferedImage? = null
        runComposeUiTest {
            setContent { GoldenSurface { WallOfText(textColor) } }
            waitForIdle()
            image = onNodeWithTag(GOLDEN_SURFACE_TAG).captureToImage().toAwtImage()
        }
        return checkNotNull(image)
    }

    @Composable
    private fun WallOfText(color: Color) {
        Column {
            repeat(LINES) { Text("Stats 42 episodes · Drama 13% · Comedy 9% · Rewatched 3", color = color, fontSize = 16.sp) }
        }
    }

    @Test
    fun the_same_frame_matches_itself() {
        val frame = render(Color(0xFF1D1B14))
        assertNull(goldenMismatch(frame, render(Color(0xFF1D1B14)), textHeavy))
    }

    @Test
    fun a_text_colour_change_fails_even_at_the_text_heavy_tolerance() {
        // The exact change #277 measured: GoldenSurface's text went from Compose's
        // default black to onBackground, and every affected golden still passed.
        val failure = goldenMismatch(render(Color.Black), render(Color(0xFF1D1B14)), textHeavy)

        // Three lines move well under 3.5% of the frame: only the palette check can see this.
        assertNull(goldenMismatch(render(Color.Black), render(Color.Black), textHeavy))
        assertNotNull(failure, "a token change on text must not hide under the area tolerance")
        assertTrue("changed colour" in failure, failure)
    }

    private companion object {
        const val LINES = 3
    }
}
