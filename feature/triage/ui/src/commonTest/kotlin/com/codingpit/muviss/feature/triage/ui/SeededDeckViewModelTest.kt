@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.codingpit.muviss.feature.triage.ui

import com.codingpit.muviss.feature.triage.api.TriageVerdict
import com.codingpit.muviss.models.Genre
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EPIC 51 (#554, #555): the deck Next Pick hands over. It is Triage's screen over the seeds, so the verdicts and undo
 * are the catalogue deck's, but the list is the engine's, read from nowhere else, and it is never saved as a position.
 */
class SeededDeckViewModelTest {

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val filmA = movie("1")
    private val filmB = movie("2")
    private val showA = show("10")

    @Test
    fun `a seeded deck offers its titles in the order handed over and reads no catalogue`() = runTest {
        val harness = TriageHarness(movies = listOf(movie("99")), tv = listOf(show("98")))
        val ranked = listOf(showA, filmA, filmB)

        val vm = harness.seededViewModel(ranked)
        advanceUntilIdle()

        assertTrue(vm.state.value.seeded)
        assertEquals(ranked.map { it.id }, vm.state.value.cards.map { it.id })
        assertEquals(emptyList(), harness.source.requestedPages)
        assertEquals(emptyList(), harness.source.tvExcludedRequests)
    }

    @Test
    fun `a seeded deck has no genre chips to narrow it by`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA), genres = listOf(Genre("28", "Action"))).seededViewModel(listOf(filmA))
        advanceUntilIdle()

        assertTrue(vm.state.value.movieGenres.isEmpty())
        assertTrue(vm.state.value.tvGenres.isEmpty())
    }

    @Test
    fun `an empty handoff is the picks done state and never reads the catalogue`() = runTest {
        val harness = TriageHarness(movies = listOf(filmA, filmB))

        val vm = harness.seededViewModel(emptyList())
        advanceUntilIdle()

        assertNull(vm.state.value.topCard)
        assertTrue(vm.state.value.exhausted)
        assertFalse(vm.state.value.keepLookingAvailable)
        assertFalse(vm.state.value.searchingDeeper)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
        assertEquals(emptyList(), harness.source.requestedPages)
    }

    @Test
    fun `a seeded deck that runs out is exhausted and never offers to keep looking`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()

        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        assertNull(vm.state.value.topCard)
        assertTrue(vm.state.value.exhausted)
        assertFalse(vm.state.value.keepLookingAvailable)
    }

    @Test
    fun `a seeded deck saves no position even as it is swiped while a catalogue deck does`() = runTest {
        val store = FakeDeckCursorStore()
        val harness = TriageHarness(movies = listOf(filmA, filmB), cursors = store)

        val seeded = harness.seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()
        seeded.onDecide(TriageVerdict.LATER, viaGesture = false)
        seeded.onDecide(TriageVerdict.SKIP, viaGesture = false)
        advanceUntilIdle()
        assertTrue(store.saved.isEmpty(), "a seeded deck must not overwrite the catalogue position")

        // The same store is written by the catalogue deck, so the assertion above is not vacuous.
        val catalogue = harness.viewModel()
        advanceUntilIdle()
        catalogue.onDecide(TriageVerdict.SKIP, viaGesture = false)
        advanceUntilIdle()
        assertTrue(store.saved.isNotEmpty())
    }

    @Test
    fun `a seed swiped away stays away through a reset`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf(filmB.id), vm.state.value.cards.map { it.id })
    }

    @Test
    fun `a seed snoozed stays out through a reset`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()
        // The default period is a week, so the snooze commits at once rather than opening the sheet.
        vm.onSnooze()
        advanceUntilIdle()

        vm.retry()
        advanceUntilIdle()

        assertEquals(listOf(filmB.id), vm.state.value.cards.map { it.id })
    }

    @Test
    fun `undo of a seed puts it back on top`() = runTest {
        val vm = TriageHarness(movies = listOf(filmA, filmB)).seededViewModel(listOf(filmA, filmB))
        advanceUntilIdle()
        vm.onDecide(TriageVerdict.LATER, viaGesture = false)
        advanceUntilIdle()

        vm.onUndo()
        advanceUntilIdle()

        assertEquals(filmA.id, vm.state.value.topCard?.id)
    }
}
