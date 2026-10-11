@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.settings.ui

import com.codingpit.muviss.feature.settings.domain.ClearTmdbKeyUseCase
import com.codingpit.muviss.feature.settings.domain.ObserveTmdbDataSourceUseCase
import com.codingpit.muviss.feature.settings.domain.SaveTmdbKeyResult
import com.codingpit.muviss.feature.settings.domain.SaveTmdbKeyUseCase
import com.codingpit.muviss.feature.settings.domain.TmdbDataSource
import com.codingpit.muviss.feature.settings.domain.TmdbKeySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EPIC 46: the row is offered while Remote Config allows it, and stays while a key is in use. */
class TmdbKeyViewModelTest {

    private class FakeTmdbKeySettings : TmdbKeySettings {
        val source = MutableStateFlow(TmdbDataSource.MUVISS)
        var result = SaveTmdbKeyResult.SAVED
        val saved = mutableListOf<String>()

        override fun observeSource(): Flow<TmdbDataSource> = source

        override suspend fun save(raw: String): SaveTmdbKeyResult {
            saved += raw
            if (result == SaveTmdbKeyResult.SAVED) source.value = TmdbDataSource.YOURS
            return result
        }

        override suspend fun clear() {
            source.value = TmdbDataSource.MUVISS
        }
    }

    private val settings = FakeTmdbKeySettings()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NoFlags.byokEnabled.value = false
    }

    @AfterTest
    fun tearDown() {
        NoFlags.byokEnabled.value = false
        Dispatchers.resetMain()
    }

    private fun viewModel() = TmdbKeyViewModel(
        ObserveTmdbDataSourceUseCase(settings),
        NoFlags,
        SaveTmdbKeyUseCase(settings),
        ClearTmdbKeyUseCase(settings),
    )

    @Test
    fun the_row_is_hidden_until_the_app_offers_it() = runTest {
        assertFalse(viewModel().state.value.visible)
    }

    @Test
    fun the_row_appears_when_the_app_offers_it() = runTest {
        val vm = viewModel()
        NoFlags.byokEnabled.value = true
        assertTrue(vm.state.value.visible)
    }

    @Test
    fun a_key_in_use_keeps_the_row_when_the_offer_is_withdrawn() = runTest {
        NoFlags.byokEnabled.value = true
        val vm = viewModel()
        vm.save("a".repeat(32)) {}
        NoFlags.byokEnabled.value = false

        assertEquals(TmdbDataSource.YOURS, vm.state.value.source)
        assertTrue(vm.state.value.visible, "turning the offer off must not strand a key in use")
    }

    @Test
    fun a_saved_key_reports_done_and_leaves_no_problem() = runTest {
        NoFlags.byokEnabled.value = true
        val vm = viewModel()
        var done = false
        vm.save("a".repeat(32)) { done = true }

        assertTrue(done)
        assertNull(vm.state.value.problem)
        assertFalse(vm.state.value.checking)
    }

    @Test
    fun each_failure_becomes_its_own_problem_and_is_not_reported_as_done() = runTest {
        NoFlags.byokEnabled.value = true
        val vm = viewModel()
        val expected = mapOf(
            SaveTmdbKeyResult.MALFORMED to TmdbKeyProblem.MALFORMED,
            SaveTmdbKeyResult.REJECTED to TmdbKeyProblem.REJECTED,
            SaveTmdbKeyResult.COULD_NOT_CHECK to TmdbKeyProblem.COULD_NOT_CHECK,
        )
        expected.forEach { (result, problem) ->
            settings.result = result
            var done = false
            vm.save("whatever") { done = true }
            assertEquals(problem, vm.state.value.problem, "$result")
            assertFalse(done, "$result")
            assertEquals(TmdbDataSource.MUVISS, vm.state.value.source, "a failed save must not change whose key is in use")
        }
    }

    @Test
    fun a_problem_is_forgotten_when_the_dialog_is_dismissed() = runTest {
        NoFlags.byokEnabled.value = true
        val vm = viewModel()
        settings.result = SaveTmdbKeyResult.REJECTED
        vm.save("x") {}
        vm.dismissProblem()
        assertNull(vm.state.value.problem)
    }

    @Test
    fun using_muvisss_key_clears_the_stored_one() = runTest {
        NoFlags.byokEnabled.value = true
        val vm = viewModel()
        vm.save("a".repeat(32)) {}
        var done = false
        vm.useMuvissKey { done = true }

        assertTrue(done)
        assertEquals(TmdbDataSource.MUVISS, vm.state.value.source)
    }
}
