@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tick feedback the design system owns (EPIC 52): the burst's lifetime, and a row that still works with motion off. */
class TickFeedbackTest {

    @Test
    fun the_burst_is_on_screen_for_about_a_second_then_reports_it_is_done() = runComposeUiTest {
        var finished = 0
        mainClock.autoAdvance = false
        setContent { MuvissTheme(darkTheme = false) { CelebrationBurst(label = "Caught up", onFinished = { finished++ }) } }

        mainClock.advanceTimeBy(300)
        onNodeWithTag(CELEBRATION_BURST_TAG).assertExists()
        assertEquals(0, finished)

        mainClock.advanceTimeBy(1_000)
        assertEquals(1, finished)
    }

    @Test
    fun the_burst_lets_a_tap_through_even_under_its_label() = runComposeUiTest {
        var taps = 0
        mainClock.autoAdvance = false
        setContent {
            MuvissTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag("under").clickable { taps++ })
                    CelebrationBurst(label = "Caught up", onFinished = {})
                }
            }
        }
        // The label sits in the middle and is opaque by now; a click lands on it.
        mainClock.advanceTimeBy(300)

        onNodeWithTag("under").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun a_row_ticks_whether_or_not_it_animates() = runComposeUiTest {
        var ticks = 0
        setContent {
            MuvissTheme(darkTheme = false) {
                EpisodeRow(title = "Pilot", subtitle = "S1 · E1", seen = false, onToggle = { ticks++ }, animate = false)
            }
        }

        onNodeWithContentDescription("Pilot", substring = true).performClick()

        assertEquals(1, ticks)
    }

    @Test
    fun a_row_that_becomes_seen_with_motion_off_still_shows_it_as_seen() = runComposeUiTest {
        val seen = mutableStateOf(false)
        setContent {
            MuvissTheme(darkTheme = false) {
                EpisodeRow(title = "Pilot", subtitle = "S1 · E1", seen = seen.value, onToggle = {}, animate = false)
            }
        }
        onNodeWithContentDescription("Mark", substring = true).assertExists()

        seen.value = true
        waitForIdle()

        // The toggle's label flips from "mark watched" to "mark unwatched".
        onNodeWithContentDescription("unwatched", substring = true, ignoreCase = true).assertExists()
    }
}
