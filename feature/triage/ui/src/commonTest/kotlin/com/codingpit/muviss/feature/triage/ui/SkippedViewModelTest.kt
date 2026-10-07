@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import com.codingpit.muviss.core.designsystem.text.resolveAsync
import com.codingpit.muviss.feature.triage.domain.ObserveSkippedUseCase
import com.codingpit.muviss.feature.triage.domain.RestoreDecisionUseCase
import com.codingpit.muviss.models.MetadataError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkippedViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(repository: FakeTriageDecisionRepository) = SkippedViewModel(ObserveSkippedUseCase(repository), RestoreDecisionUseCase(repository))

    @Test
    fun a_failed_read_is_an_error_not_an_empty_list() = runTest {
        // #73: this used to leave loading = false and no titles, which the
        // screen rendered as "Nothing skipped".
        val repository = FakeTriageDecisionRepository().apply { observeFailure = MetadataError.Offline() }
        val vm = viewModel(repository)
        advanceUntilIdle()

        assertEquals(MetadataError.Offline().userMessage, vm.state.value.error?.resolveAsync())
    }

    @Test
    fun a_raw_exception_never_reaches_the_screen() = runTest {
        val repository = FakeTriageDecisionRepository().apply { observeFailure = IllegalStateException("SQLITE_BUSY at /data/secret.db") }
        val vm = viewModel(repository)
        advanceUntilIdle()

        assertEquals("Couldn't load skipped titles.", vm.state.value.error?.resolveAsync())
    }

    @Test
    fun retry_resubscribes() = runTest {
        val repository = FakeTriageDecisionRepository().apply { observeFailure = MetadataError.Offline() }
        val vm = viewModel(repository)
        advanceUntilIdle()

        repository.observeFailure = null
        vm.retry()
        advanceUntilIdle()

        assertNull(vm.state.value.error)
        assertEquals(emptyList(), vm.state.value.titles)
    }
}
