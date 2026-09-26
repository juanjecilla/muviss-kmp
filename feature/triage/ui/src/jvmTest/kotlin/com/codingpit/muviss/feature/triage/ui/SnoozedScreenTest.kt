@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.formatEpochDay
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
import com.codingpit.muviss.feature.triage.domain.ObserveSnoozedUseCase
import com.codingpit.muviss.feature.triage.domain.TriageSnooze
import com.codingpit.muviss.feature.triage.domain.UnsnoozeUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The way back from a mis-snooze once the undo snackbar has gone — the gap
 * ADR 0010 refused to leave for skips, and refuses to leave here.
 */
class SnoozedScreenTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val matrix = movie("603")

    private fun harness(vararg due: Long): Pair<SnoozedViewModel, FakeTriageSnoozeRepository> {
        val repository = FakeTriageSnoozeRepository()
        repository.snoozes.value = due.mapIndexed { index, day ->
            val card = movie("60$index")
            card.id to TriageSnooze.of(card, nowEpochMs = 0L, dueAtEpochDay = day)
        }.toMap()
        return SnoozedViewModel(ObserveSnoozedUseCase(repository), UnsnoozeUseCase(repository)) to repository
    }

    @Test
    fun it_says_when_each_title_comes_back() = runComposeUiTest {
        val repository = FakeTriageSnoozeRepository()
        repository.snoozes.value = mapOf(matrix.id to TriageSnooze.of(matrix, nowEpochMs = 0L, dueAtEpochDay = 20_007L))
        val viewModel = SnoozedViewModel(ObserveSnoozedUseCase(repository), UnsnoozeUseCase(repository))

        setContent { MuvissTheme(darkTheme = false) { SnoozedScreen(viewModel, onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        // The date is the whole point of the screen: it is the one thing the
        // snackbar could not leave behind.
        onNodeWithText("Comes back ${formatEpochDay(20_007L)}").assertIsDisplayed()
    }

    @Test
    fun unsnoozing_a_row_drops_it() = runComposeUiTest {
        val (viewModel, repository) = harness(20_007L)

        setContent { MuvissTheme(darkTheme = false) { SnoozedScreen(viewModel, onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("Unsnooze").performClick()
        waitForIdle()

        assertTrue(repository.snoozes.value.isEmpty())
    }

    @Test
    fun soonest_to_come_back_is_listed_first() = runComposeUiTest {
        val (viewModel, _) = harness(20_090L, 20_007L)
        waitForIdle()

        // Deliberately the opposite ordering to the Skipped screen's
        // newest-first: what matters about a Snooze is when it returns.
        assertEquals(
            listOf(20_007L, 20_090L),
            viewModel.state.value.titles.map { it.dueAtEpochDay },
        )
    }

    @Test
    fun an_empty_list_explains_itself() = runComposeUiTest {
        val (viewModel, _) = harness()

        setContent { MuvissTheme(darkTheme = false) { SnoozedScreen(viewModel, onBack = {}, onOpenDetail = {}) } }
        waitForIdle()

        onNodeWithText("Nothing snoozed").assertIsDisplayed()
    }
}
