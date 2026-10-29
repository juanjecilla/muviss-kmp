package com.codingpit.muviss.feature.nextpick.ui

import com.codingpit.muviss.feature.nextpick.domain.CompletionPromptPolicy
import com.codingpit.muviss.feature.progress.api.Completion
import com.codingpit.muviss.feature.progress.api.CompletionKind
import com.codingpit.muviss.models.MediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionPromptViewModelTest {

    private val day = CompletionPromptPolicy.DAY_MS
    private val noon = 10 * day + day / 2

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val finished = Completion(MediaId.tmdbMovie("9"), CompletionKind.FINISHED)

    @Test
    fun a_completion_opens_the_sheet_and_records_the_day_it_appeared() {
        // Given onboarding is done, the switch is on and nothing was shown before
        val store = FakePromptStore()
        val completions = MutableSharedFlow<Completion>(extraBufferCapacity = 4)
        val viewModel = CompletionPromptViewModel(completions, flowOf(true), store, FixedClock(noon))

        // When the person finishes a title
        completions.tryEmit(finished)

        // Then the sheet is shown and the prompt is spent for the day, when it appeared
        assertTrue(viewModel.visible.value)
        assertEquals(listOf(noon), store.marks)
    }

    @Test
    fun a_completion_before_onboarding_completes_shows_nothing() {
        // Given the introduction is still pending
        val store = FakePromptStore()
        val completions = MutableSharedFlow<Completion>(extraBufferCapacity = 4)
        val viewModel = CompletionPromptViewModel(completions, flowOf(false), store, FixedClock(noon))

        // When a completion happens
        completions.tryEmit(finished)

        // Then no sheet and no spent prompt
        assertFalse(viewModel.visible.value)
        assertTrue(store.marks.isEmpty())
    }

    @Test
    fun a_second_completion_the_same_day_is_quiet() {
        // Given the sheet already appeared earlier today
        val store = FakePromptStore(lastShown = noon - 60_000L)
        val completions = MutableSharedFlow<Completion>(extraBufferCapacity = 4)
        val viewModel = CompletionPromptViewModel(completions, flowOf(true), store, FixedClock(noon))

        // When another completion happens
        completions.tryEmit(finished)

        // Then it stays hidden
        assertFalse(viewModel.visible.value)
    }

    @Test
    fun not_now_hides_the_sheet_without_another_prompt_that_day() {
        // Given a sheet on screen
        val store = FakePromptStore()
        val completions = MutableSharedFlow<Completion>(extraBufferCapacity = 4)
        val viewModel = CompletionPromptViewModel(completions, flowOf(true), store, FixedClock(noon))
        completions.tryEmit(finished)

        // When the person dismisses it
        viewModel.dismiss()

        // Then it is gone, and a later completion the same day still does not bring it back
        assertFalse(viewModel.visible.value)
        completions.tryEmit(finished)
        assertFalse(viewModel.visible.value)
    }

    @Test
    fun a_disabled_switch_suppresses_the_sheet_entirely() {
        // Given the completion prompt is switched off
        val store = FakePromptStore(enabled = false)
        val completions = MutableSharedFlow<Completion>(extraBufferCapacity = 4)
        val viewModel = CompletionPromptViewModel(completions, flowOf(true), store, FixedClock(noon))

        // When a completion happens
        completions.tryEmit(finished)

        // Then nothing is shown and nothing is recorded
        assertFalse(viewModel.visible.value)
        assertTrue(store.marks.isEmpty())
    }
}
