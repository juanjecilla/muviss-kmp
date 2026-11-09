@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.AppVersion
import com.codingpit.muviss.core.common.remoteconfig.DefaultsRemoteConfig
import com.codingpit.muviss.core.common.remoteconfig.FeatureRequestProvider
import com.codingpit.muviss.core.common.remoteconfig.RemoteValues
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.core.testing.FakeClock
import com.codingpit.muviss.feature.settings.domain.DeleteAllDataUseCase
import com.codingpit.muviss.feature.settings.domain.LocalDataEraser
import com.codingpit.muviss.feature.settings.domain.ObserveSettingsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Feature Request row (ADR 0035): hidden until there is a form, and it hands the link to its opener. */
class FeatureRequestRowTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the row is absent while there is no form`() = runComposeUiTest {
        setContent {
            MuvissTheme(darkTheme = false) {
                AboutSection(appVersionName = "1.0.0", onOpenLicenses = {}, onTestCrash = {})
            }
        }

        onNodeWithTag(FEATURE_REQUEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `tapping the row opens the form`() = runComposeUiTest {
        var opened = 0
        setContent {
            MuvissTheme(darkTheme = false) {
                AboutSection(appVersionName = "1.0.0", onOpenLicenses = {}, onTestCrash = {}, onRequestFeature = { opened++ })
            }
        }

        onNodeWithTag(FEATURE_REQUEST_TAG).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the bug report row is absent while there is no form and opens its form when tapped`() = runComposeUiTest {
        var shown by mutableStateOf(false)
        var opened = 0
        setContent {
            MuvissTheme(darkTheme = false) {
                AboutSection(
                    appVersionName = "1.0.0",
                    onOpenLicenses = {},
                    onTestCrash = {},
                    onReportProblem = if (shown) ({ opened++ }) else null,
                )
            }
        }

        onNodeWithTag(BUG_REPORT_TAG).assertDoesNotExist()
        shown = true
        onNodeWithTag(BUG_REPORT_TAG).performClick()

        assertEquals(1, opened)
    }

    @Test
    fun `the view model carries no link by default and a tagged one once a form is served`() {
        val repository = FakeRepository()
        assertNull(settingsViewModel(repository).state.value.featureRequestUrl)
        assertNull(settingsViewModel(repository).state.value.bugReportUrl)

        val remote = DefaultsRemoteConfig(
            RemoteValues(
                byokEnabled = false,
                featureRequestProvider = FeatureRequestProvider.Tally,
                featureRequestUrl = "https://tally.so/r/abc",
                bugReportUrl = "https://tally.so/r/bug",
            ),
        )
        val vm = SettingsViewModel(
            settingsObservations(ObserveSettingsUseCase(repository), remote),
            settingsActions(repository),
            AppVersion(versionName = "1.0.0", versionCode = 1),
            NoFlags,
            DeleteAllDataUseCase(
                object : LocalDataEraser {
                    override suspend fun deleteAllData() = Unit
                },
            ),
            FakeClock(0L),
        )

        val url = vm.state.value.featureRequestUrl
        assertEquals(true, url?.startsWith("https://tally.so/r/abc?version=1.0.0&platform=desktop"), url)
        assertEquals(true, vm.state.value.bugReportUrl?.startsWith("https://tally.so/r/bug?version=1.0.0"), vm.state.value.bugReportUrl)
    }
}
