@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.settings.domain.OssLicense
import kotlin.test.Test

/** A row on About > Licenses shows every licence record its artifact carries (#615), and nothing it does not. */
class LicensesScreenTest {

    @Test
    fun `a dual-licensed artifact shows both of its licences`() = runComposeUiTest {
        val jna = OssLicense(
            groupId = "net.java.dev.jna",
            artifactId = "jna",
            name = "Java Native Access",
            licenseName = "Apache License 2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            secondLicenseName = "LGPL-2.1-or-later",
            secondLicenseUrl = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1",
        )
        setContent { MuvissTheme(darkTheme = false) { LicenseRow(jna) } }

        onNodeWithText("Apache License 2.0").assertIsDisplayed()
        onNodeWithText("LGPL-2.1-or-later").assertIsDisplayed()
    }

    @Test
    fun `an artifact with one licence shows no second line`() = runComposeUiTest {
        val single = OssLicense(
            groupId = "androidx.annotation",
            artifactId = "annotation",
            name = "Annotation",
            licenseName = "Apache License 2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
        )
        setContent { MuvissTheme(darkTheme = false) { LicenseRow(single) } }

        onNodeWithText("Apache License 2.0").assertIsDisplayed()
        onAllNodesWithText("LGPL-2.1-or-later").assertCountEquals(0)
    }
}
