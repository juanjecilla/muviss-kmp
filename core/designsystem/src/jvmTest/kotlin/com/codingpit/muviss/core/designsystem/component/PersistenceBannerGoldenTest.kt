@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GOLDEN_SURFACE_TAG
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The banner is the only thing standing between a web user in a second tab and
 * silently losing everything they do there, so what it looks like matters as
 * much as whether it renders: a warning that reads as decoration is not a
 * warning. Hence a golden rather than only a text assertion.
 *
 * `darkTheme` is passed explicitly because `MuvissTheme` otherwise follows the
 * host machine's setting, and a golden recorded in dark mode fails on CI with
 * nearly every pixel moved. Both themes are recorded, since a warning colour
 * that holds up on one background can wash out on the other.
 */
class PersistenceBannerGoldenTest {

    private val readOnlyTab = "Open in another tab. Changes made here won't be saved; reloading discards them."
    private val staleTab = "Another tab saved newer changes, so this tab won't save. Reloading discards its changes."
    private val reload = "Reload this tab"
    private val shortReload = "Reload"

    // Short enough that a short action fits beside it; the long warning above wraps the action by design.
    private val shortWarning = "Not saving here."
    private val longReload = "Reload this tab to start saving again and keep everything"

    @Test
    fun the_read_only_tab_warning_is_legible() = runComposeUiTest {
        setContent { BannerSample(message = readOnlyTab, darkTheme = true) }

        assertMatchesGolden("persistence-banner-read-only-tab", tolerance = BANNER_TOLERANCE)
    }

    @Test
    fun the_read_only_tab_warning_is_legible_light() = runComposeUiTest {
        setContent { BannerSample(message = readOnlyTab, darkTheme = false) }

        assertMatchesGolden("persistence-banner-read-only-tab-light", tolerance = BANNER_TOLERANCE)
    }

    /**
     * The out-of-date warning with its reload action (#62). The action is the only
     * thing that changes the banner's shape, so it gets its own recording. The
     * action wraps onto a second line here, so the frame is 100 dp rather than the
     * single-line default. Measured: at 96 dp `FlowRow` does not place the action
     * at all, and it first places at 100 dp. A short frame would record a warning
     * with no button, which is what the placement checks below catch.
     */
    @Test
    fun the_stale_tab_warning_offers_a_reload() = runComposeUiTest {
        setContent {
            BannerSample(message = staleTab, darkTheme = true, height = 100.dp, actionLabel = reload)
        }

        val message = onNodeWithText(staleTab).getBoundsInRoot()
        val action = onNodeWithText(reload).getBoundsInRoot()
        assertTrue(action.top >= message.bottom, "the action should wrap below the warning, not beside it")
        assertActionInsideFrame(reload)
        assertMatchesGolden("persistence-banner-stale-reload", tolerance = BANNER_TOLERANCE)
    }

    /**
     * #881: a long action label must not squeeze the warning into a narrow column. The action wraps below the
     * text, so the text keeps the full width, and the golden shows it.
     */
    @Test
    fun the_warning_stays_readable_when_the_action_label_is_long() = runComposeUiTest {
        setContent {
            BannerSample(message = readOnlyTab, darkTheme = true, height = 136.dp, actionLabel = longReload)
        }

        val message = onNodeWithText(readOnlyTab).getBoundsInRoot()
        val action = onNodeWithText(longReload).getBoundsInRoot()
        assertTrue(action.top >= message.bottom, "the action should wrap below the warning, not beside it")
        assertActionInsideFrame(longReload)
        assertMatchesGolden("persistence-banner-long-action", tolerance = BANNER_TOLERANCE)
    }

    /** #881: with a short action there is room beside the text, so the banner stays one row. */
    @Test
    fun a_short_action_sits_beside_the_warning() = runComposeUiTest {
        setContent { BannerSample(message = shortWarning, darkTheme = false, actionLabel = shortReload) }

        val message = onNodeWithText(shortWarning).getBoundsInRoot()
        val action = onNodeWithText(shortReload).getBoundsInRoot()
        assertTrue(action.top < message.bottom, "a short action stays on the warning's line")
        assertActionInsideFrame(shortReload)
        assertMatchesGolden("persistence-banner-short-action-light", tolerance = BANNER_TOLERANCE)
    }

    /**
     * `Durable` and `Pending` both arrive here as a null message, and both must
     * draw nothing — Pending especially, since it is what every writer tab
     * passes through on load and a flash of this banner would read as a bug.
     */
    @Test
    fun a_null_message_draws_nothing() = runComposeUiTest {
        setContent { BannerSample(message = null, darkTheme = true) }

        onAllNodes(hasText(readOnlyTab, substring = true)).assertCountEquals(0)
        assertMatchesGolden("persistence-banner-absent")
    }

    @Test
    fun a_null_message_draws_nothing_light() = runComposeUiTest {
        setContent { BannerSample(message = null, darkTheme = false) }

        onAllNodes(hasText(readOnlyTab, substring = true)).assertCountEquals(0)
        assertMatchesGolden("persistence-banner-absent-light")
    }
}

/**
 * The golden crops to its frame, and a line that does not fit the frame's height
 * is not placed at all. Either way the action would be missing from the recording
 * while the test still passed, so this asserts it is displayed and inside the frame.
 */
private fun ComposeUiTest.assertActionInsideFrame(label: String) {
    val frame = onNodeWithTag(GOLDEN_SURFACE_TAG).getBoundsInRoot()
    onNodeWithText(label).assertIsDisplayed()
    val action = onNodeWithText(label).getBoundsInRoot()
    assertTrue(action.bottom <= frame.bottom, "the action must not be cropped by the golden frame")
    assertTrue(action.right <= frame.right, "the action must not be cropped by the golden frame")
}

@Composable
private fun BannerSample(
    message: String?,
    darkTheme: Boolean,
    height: Dp = 72.dp,
    actionLabel: String? = null,
) {
    MuvissTheme(darkTheme = darkTheme) {
        GoldenSurface(width = 412.dp, height = height) {
            PersistenceBanner(
                message = message?.let(UiText::Raw),
                actionLabel = actionLabel?.let(UiText::Raw),
            )
        }
    }
}

/**
 * The frame is 412x72dp and the text fills most of it, so the glyph edges
 * CoreText and FreeType draw differently are a large share of the image: every
 * marked pixel is a glyph edge against a macOS recording with hinting pinned
 * (`GoldenSurface`, #171). Measured on `ubuntu-latest` (run 43586953160, #840):
 * read-only tab 4.25%, its light variant 4.12%, the stale-reload warning 6.14%
 * (two lines of text plus the action). The tolerance sits above the worst of
 * those. A colour change, what this golden exists to catch, is refused by the
 * palette check before this is reached, and moves the whole banner far above
 * this anyway.
 */
private const val BANNER_TOLERANCE = 0.08
