@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.GoldenSurface
import com.codingpit.muviss.core.testing.assertMatchesGolden
import kotlin.test.Test

/**
 * The banner is the only thing standing between a web user in a second tab and
 * silently losing everything they do there, so what it looks like matters as
 * much as whether it renders: a warning that reads as decoration is not a
 * warning. Hence a golden rather than only a text assertion.
 *
 * `darkTheme` is passed explicitly because `MuvissTheme` otherwise follows the
 * host machine's setting, and a golden recorded in dark mode fails on CI with
 * nearly every pixel moved.
 */
class PersistenceBannerGoldenTest {

    private val readOnlyTab = "Muviss is open in another tab. Changes here won't be saved."

    @Test
    fun the_read_only_tab_warning_is_legible() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                GoldenSurface(width = 412.dp, height = 72.dp) {
                    PersistenceBanner(message = readOnlyTab)
                }
            }
        }

        assertMatchesGolden("persistence-banner-read-only-tab")
    }

    /**
     * `Durable` and `Pending` both arrive here as a null message, and both must
     * draw nothing — Pending especially, since it is what every writer tab
     * passes through on load and a flash of this banner would read as a bug.
     */
    @Test
    fun a_null_message_draws_nothing() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                GoldenSurface(width = 412.dp, height = 72.dp) {
                    PersistenceBanner(message = null)
                }
            }
        }

        onAllNodes(hasText(readOnlyTab, substring = true)).assertCountEquals(0)
        assertMatchesGolden("persistence-banner-absent")
    }
}
