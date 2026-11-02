@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.text.OwnKeyRejectedText
import com.codingpit.muviss.core.designsystem.text.UiText
import com.codingpit.muviss.core.designsystem.text.toUiText
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.models.MetadataError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #321: a rejected Own TMDB Key is the one error where Retry cannot help, so
 * it also offers the way to the key. Every other error stays the plain state.
 */
class ErrorStateTest {

    private val openSettings = "Open Settings"

    @Test
    fun a_rejected_own_key_offers_settings_and_the_button_opens_it() = runComposeUiTest {
        var opened = 0
        setContent {
            MuvissTheme(darkTheme = true) {
                CompositionLocalProvider(LocalOpenTmdbKeySettings provides { opened++ }) {
                    ErrorState(MetadataError.OwnKeyRejected().toUiText(UiText.Raw("fallback")), onRetry = {})
                }
            }
        }

        onNodeWithText(openSettings).performClick()

        assertEquals(1, opened)
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun any_other_error_has_only_retry() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                CompositionLocalProvider(LocalOpenTmdbKeySettings provides {}) {
                    ErrorState(MetadataError.Offline().toUiText(UiText.Raw("fallback")), onRetry = {})
                }
            }
        }

        onAllNodesWithText(openSettings).assertCountEquals(0)
        onNodeWithText("Retry").assertExists()
    }

    @Test
    fun a_screen_with_no_way_to_settings_shows_no_dead_button() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = true) {
                ErrorState(OwnKeyRejectedText, onRetry = {})
            }
        }

        onAllNodesWithText(openSettings).assertCountEquals(0)
        onNodeWithText("Retry").assertExists()
    }
}
