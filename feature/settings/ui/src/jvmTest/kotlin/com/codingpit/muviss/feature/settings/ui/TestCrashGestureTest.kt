@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hidden "Send test crash" action is how a release build's crash reporting
 * is verified end to end, so it must stay reachable — and stay hidden from
 * anyone who has not tapped the version on purpose. The crash itself is a fake
 * sink here; a real throw would take the test JVM's thread with it.
 */
class TestCrashGestureTest {

    @Test
    fun `the action stays hidden until the version is tapped seven times`() = runComposeUiTest {
        var crashes = 0
        setContent {
            MuvissTheme(darkTheme = false) {
                AboutSection(appVersionName = "1.0.0", onOpenLicenses = {}, onTestCrash = { crashes++ })
            }
        }

        repeat(TEST_CRASH_TAPS - 1) { onNodeWithTag(VERSION_ROW_TAG).performClick() }
        onNodeWithText(TEST_CRASH_LABEL).assertDoesNotExist()

        onNodeWithTag(VERSION_ROW_TAG).performClick()
        onNodeWithText(TEST_CRASH_LABEL).performClick()

        assertEquals(1, crashes)
    }
}
