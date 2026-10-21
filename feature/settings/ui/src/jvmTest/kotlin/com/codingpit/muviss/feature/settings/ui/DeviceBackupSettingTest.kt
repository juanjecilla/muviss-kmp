@file:OptIn(ExperimentalTestApi::class)

package com.codingpit.muviss.feature.settings.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Device Backup row (ADR 0034): hidden where the OS cannot back up, off by default, and a tap reaches the store. */
class DeviceBackupSettingTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun the_row_is_hidden_where_the_platform_has_no_os_backup_to_honour() = runComposeUiTest {
        val repository = FakeRepository()
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(settingsViewModel(repository), {}, {}, {}, canBackUp = false) }
        }
        waitForIdle()

        assertTrue(onAllNodesWithTag(DEVICE_BACKUP_ROW_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun the_row_starts_off_and_a_tap_turns_device_backup_on_in_the_store() = runComposeUiTest {
        val repository = FakeRepository()
        setContent {
            MuvissTheme(darkTheme = false) { SettingsScreen(settingsViewModel(repository), {}, {}, {}, canBackUp = true) }
        }
        waitForIdle()

        onNodeWithText("Back up with this device").performScrollTo()
        onNodeWithTag(DEVICE_BACKUP_ROW_TAG).assertIsOff()
        assertFalse(repository.settings.value.deviceBackupEnabled)

        onNodeWithTag(DEVICE_BACKUP_ROW_TAG).performClick()
        waitForIdle()

        assertTrue(repository.settings.value.deviceBackupEnabled)
        onNodeWithTag(DEVICE_BACKUP_ROW_TAG).assertIsOn()
    }
}
