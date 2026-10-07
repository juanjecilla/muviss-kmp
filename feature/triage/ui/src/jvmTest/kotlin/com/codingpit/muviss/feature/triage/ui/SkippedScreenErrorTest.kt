@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.triage.domain.ObserveSkippedUseCase
import com.codingpit.muviss.feature.triage.domain.RestoreDecisionUseCase
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class SkippedScreenErrorTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun a_failed_read_shows_an_error_with_retry_instead_of_nothing_skipped() = runComposeUiTest {
        val repository = FakeTriageDecisionRepository().apply { observeFailure = MetadataError.Offline() }
        val vm = SkippedViewModel(ObserveSkippedUseCase(repository), RestoreDecisionUseCase(repository))

        setContent { MuvissTheme(darkTheme = false) { SkippedScreen(vm, onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage).assertExists()
        onNodeWithText("Nothing skipped", substring = true).assertDoesNotExist()

        repository.observeFailure = null
        onNodeWithText("Retry").performClick()
        waitForIdle()

        onNodeWithText(MetadataError.Offline().userMessage).assertDoesNotExist()
    }
}
