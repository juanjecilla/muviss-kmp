@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.onboarding.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.Res
import com.codingpit.muviss.feature.onboarding.ui.generated.resources.onboarding_key_muviss
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

/**
 * Compose Resources does not read `\'` as an escape: a value written `Muviss\'s` renders the
 * backslash. This pins one string with an apostrophe to its exact rendered text (#598).
 */
class ApostropheRenderingTest {

    @Test
    fun a_resource_apostrophe_renders_as_a_plain_apostrophe() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                Text(stringResource(Res.string.onboarding_key_muviss), modifier = Modifier.testTag(APOSTROPHE_TAG))
            }
        }
        onNodeWithTag(APOSTROPHE_TAG).assertTextEquals("Using Muviss's key")
    }

    private companion object {
        const val APOSTROPHE_TAG = "apostrophe-probe"
    }
}
