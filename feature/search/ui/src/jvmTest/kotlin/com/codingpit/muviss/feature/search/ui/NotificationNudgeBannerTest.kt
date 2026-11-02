@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** EPIC 47: the nudge names the show, offers one tap to turn alerts on, and can be put away. */
class NotificationNudgeBannerTest {

    @Test
    fun it_names_the_show_and_wires_both_answers() = runComposeUiTest {
        var turnedOn = 0
        var dismissed = 0
        setContent {
            MuvissTheme(darkTheme = false) {
                NotificationNudgeBanner("Severance", blocked = false, onTurnOn = { turnedOn++ }, onDismiss = { dismissed++ }, onOpenSettings = {})
            }
        }

        onNodeWithText("Get told when new episodes of Severance air?").assertExists()
        onNodeWithTag(NUDGE_TURN_ON_TAG).performClick()
        onNodeWithTag(NUDGE_DISMISS_TAG).performClick()

        assertEquals(1, turnedOn)
        assertEquals(1, dismissed)
    }

    @Test
    fun a_refusal_swaps_the_question_for_the_reason_and_a_way_out() = runComposeUiTest {
        var opened = 0
        setContent {
            MuvissTheme(darkTheme = false) {
                NotificationNudgeBanner("Severance", blocked = true, onTurnOn = {}, onDismiss = {}, onOpenSettings = { opened++ })
            }
        }

        onNodeWithText("Notifications are blocked for Muviss in the system settings.").assertExists()
        onNodeWithTag(NUDGE_TURN_ON_TAG).assertDoesNotExist()
        onNodeWithText("Open settings").performClick()
        assertEquals(1, opened)
    }
}
