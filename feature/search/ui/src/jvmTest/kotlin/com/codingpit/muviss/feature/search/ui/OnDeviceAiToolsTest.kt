@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.search.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.ai.LocalOnDeviceAi
import com.codingpit.muviss.core.designsystem.ai.OnDeviceAi
import com.codingpit.muviss.core.designsystem.ai.OnDeviceFeature
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlin.test.Test

/**
 * The on-device buttons: absent when the device cannot, present and announced when it can, honest when it fails.
 * The model is a fake: what is tested is the screen, not Gemini Nano (that needs a real device, see docs/RUNBOOK.md).
 */
class OnDeviceAiToolsTest {

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private class Fake(
        private val shortened: String? = "FAKE SHORT",
        private val described: String? = "FAKE DESCRIPTION",
        private val features: Set<OnDeviceFeature> = OnDeviceFeature.entries.toSet(),
    ) : OnDeviceAi {
        override suspend fun isAvailable(feature: OnDeviceFeature) = feature in features

        override suspend fun describeImage(imageUrl: String) = described

        override suspend fun shorten(text: String, languageTag: String) = shortened
    }

    @Test
    fun it_draws_nothing_on_a_device_that_cannot() = runComposeUiTest {
        setContent { MuvissTheme(darkTheme = true) { OnDeviceAiTools(overview = "A long synopsis", backdropUrl = "https://image") } }
        waitForIdle()

        onAllNodes(button).assertCountEquals(0)
    }

    @Test
    fun it_offers_a_button_per_feature_the_device_has() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalOnDeviceAi provides Fake(features = setOf(OnDeviceFeature.Shorten))) {
                MuvissTheme(darkTheme = true) { OnDeviceAiTools(overview = "A long synopsis", backdropUrl = "https://image") }
            }
        }
        waitForIdle()

        onAllNodes(button).assertCountEquals(1)
    }

    @Test
    fun a_shortened_synopsis_appears_when_asked() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalOnDeviceAi provides Fake()) {
                MuvissTheme(darkTheme = true) { OnDeviceAiTools(overview = "A long synopsis", backdropUrl = null) }
            }
        }
        waitForIdle()
        onNode(button).performClick()
        waitForIdle()

        onNodeWithText("FAKE SHORT").assertIsDisplayed()
    }

    @Test
    fun a_description_appears_when_asked() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalOnDeviceAi provides Fake()) {
                MuvissTheme(darkTheme = true) { OnDeviceAiTools(overview = null, backdropUrl = "https://image") }
            }
        }
        waitForIdle()
        onNode(button).performClick()
        waitForIdle()

        onNodeWithText("FAKE DESCRIPTION").assertIsDisplayed()
    }

    @Test
    fun a_failure_leaves_the_user_with_a_message_not_a_blank() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalOnDeviceAi provides Fake(shortened = null)) {
                MuvissTheme(darkTheme = true) { OnDeviceAiTools(overview = "A long synopsis", backdropUrl = null) }
            }
        }
        waitForIdle()
        onNode(button).performClick()
        waitForIdle()

        // The live region that held nothing now holds the message; the other one is still empty.
        onAllNodes(SemanticsMatcher("live region with content") { it.config.contains(SemanticsProperties.LiveRegion) && it.children.isNotEmpty() })
            .assertCountEquals(1)
        onNodeWithText("FAKE SHORT").assertDoesNotExist()
    }
}
