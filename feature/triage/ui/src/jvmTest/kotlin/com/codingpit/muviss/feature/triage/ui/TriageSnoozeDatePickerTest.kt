@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.codingpit.muviss.feature.triage.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.codingpit.muviss.core.common.flags.SnoozePeriod
import com.codingpit.muviss.core.common.todayEpochDay
import com.codingpit.muviss.core.designsystem.theme.MuvissTheme
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
 * Issue #137: the free-form date under [SnoozePeriod.ASK_EACH_TIME], reached
 * through [TRIAGE_SNOOZE_PICK_DATE_TAG] rather than Material3's `DatePicker`
 * (unverified on `js`/`wasmJs` — see [SnoozeDatePickerDialog]'s KDoc).
 */
class TriageSnoozeDatePickerTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val filmA = movie("1")
    private val filmB = movie("2")
    private val today = FakeClock().todayEpochDay()

    private fun ComposeUiTest.openDatePicker(harness: TriageHarness): TriageViewModel {
        harness.flags.snoozePeriod.value = SnoozePeriod.ASK_EACH_TIME
        harness.preferences.snoozeHint.value = true
        val viewModel = harness.viewModel()
        setContent {
            MuvissTheme {
                TriageScreen(viewModel = viewModel, onBack = {}, onOpenSkipped = {}, onOpenSnoozed = {}, onOpenDetail = {})
            }
        }
        waitForIdle()

        onNodeWithTag(TRIAGE_SNOOZE_TAG).performClick()
        waitForIdle()
        onNodeWithTag(TRIAGE_SNOOZE_PICK_DATE_TAG).performClick()
        waitForIdle()
        return viewModel
    }

    @Test
    fun pick_a_date_replaces_the_preset_sheet_with_the_calendar() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        openDatePicker(harness)

        onNodeWithTag(TRIAGE_DATE_PICKER_TAG).assertIsDisplayed()
        // The presets are gone, not stacked underneath — Cancel here must not
        // fall back to the sheet it replaced.
        assertEquals(0, onAllNodesWithTextCount("1 week"))
    }

    @Test
    fun confirm_is_disabled_until_a_day_is_picked() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        openDatePicker(harness)

        onNodeWithText("Snooze").assertIsNotEnabled()
    }

    @Test
    fun today_is_not_a_selectable_day() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        openDatePicker(harness)

        // The lower bound is tomorrow: "snoozing" into today or the past
        // makes no sense, so today's own cell is rendered disabled.
        onNodeWithTag("$TRIAGE_DATE_PICKER_DAY_TAG:$today").assertIsNotEnabled()
    }

    @Test
    fun picking_a_day_and_confirming_snoozes_to_exactly_that_day() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        openDatePicker(harness)

        val target = today + 6
        onNodeWithTag("$TRIAGE_DATE_PICKER_DAY_TAG:$target").performClick()
        waitForIdle()
        onNodeWithText("Snooze").performClick()
        waitForIdle()

        assertEquals(target, harness.snoozes.snoozes.value.getValue(filmA.id).dueAtEpochDay)
        // A Snooze is not a verdict (ADR 0023): nothing is decided, nothing saved.
        assertTrue(harness.repository.decisions.value.isEmpty())
    }

    @Test
    fun the_earliest_month_cannot_be_navigated_further_back() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        openDatePicker(harness)

        onNodeWithContentDescription("Previous month").assertIsNotEnabled()
    }

    @Test
    fun next_month_shows_that_month_s_own_days() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        openDatePicker(harness)

        onNodeWithContentDescription("Next month").performClick()
        waitForIdle()

        // Now reachable, because the visible month is no longer the earliest one.
        onNodeWithContentDescription("Previous month").assertIsDisplayed()
    }

    @Test
    fun cancel_leaves_the_deck_untouched() = runComposeUiTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))
        val viewModel = openDatePicker(harness)

        onNodeWithTag("$TRIAGE_DATE_PICKER_DAY_TAG:${today + 6}").performClick()
        waitForIdle()
        onNodeWithText("Cancel").performClick()
        waitForIdle()

        assertTrue(harness.snoozes.snoozes.value.isEmpty())
        assertEquals(filmA.id, viewModel.state.value.topCard?.id)
    }

    private fun ComposeUiTest.onAllNodesWithTextCount(text: String): Int = onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
